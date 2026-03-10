package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Parallel
import cats.data.Validated
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceManager
import io.constellationnetwork.node.shared.domain.snapshot.SnapshotContextFunctions
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.TimeTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global.GlobalSnapshotAcceptanceManager
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.transaction.RewardTransaction
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelValidationType}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.types.all.NonNegLong
import io.circe.Json
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class GlobalSnapshotContextFunctions[F[_]] extends SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]

object GlobalSnapshotContextFunctions {

  def make[F[_]: Async: Parallel: HasherSelector: SecurityProvider: JsonSerializer](
    snapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[F],
    updateDelegatedStakeAcceptanceManager: UpdateDelegatedStakeAcceptanceManager[F],
    withdrawalTimeLimit: EpochProgress,
    tessellation3MigrationStartingOrdinal: SnapshotOrdinal,
    setSumFixOrdinal: SnapshotOrdinal,
    mptStore: MptStore[F, GlobalStateKey],
    incrementalDelegatedStakingStartingOrdinal: SnapshotOrdinal
  )(
    implicit globalStateProofSelector: GlobalStateProofSelector
  ) =
    new GlobalSnapshotContextFunctions[F] {
      private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

      // Note: State proof validation is skipped for followers (currency-l0, dag-l1) because:
      // 1. The snapshot was already validated by dag-l0 majority consensus
      // 2. Full MPT sync on every ordinal is too expensive (~2 min for 800K entries)
      // The MPT store is synced once during initial download for query support.

      // todo - avoid duplication of this function here and in GlobalSnapshotConsensusFunctions
      private def acceptDelegatedStakes(
        lastSnapshotContext: GlobalSnapshotInfo,
        epochProgress: EpochProgress,
        ordinal: SnapshotOrdinal,
        acceptedTokenLocks: List[Signed[TokenLock]]
      )(implicit h: Hasher[F]): F[
        (
          SortedMap[Address, SortedSet[DelegatedStakeRecord]],
          SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
          SortedMap[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
        )
      ] = {
        val existingDelegatedStakesRaw = lastSnapshotContext.activeDelegatedStakes.getOrElse(
          SortedMap.empty[Address, SortedSet[DelegatedStakeRecord]]
        )

        val existingDelegatedStakesParsed = existingDelegatedStakesRaw.view.mapValues { records =>
          records.map { r =>
            r.copy(
              currentTokenLockRef = r.currentTokenLockRef.orElse(r.tokenLockRef.some),
              currentAmount = r.currentAmount.orElse(r.amount.some)
            )
          }
        }.to(SortedMap)

        val existingDelegatedStakesNormalized =
          if (ordinal > incrementalDelegatedStakingStartingOrdinal)
            existingDelegatedStakesParsed
          else
            existingDelegatedStakesRaw

        val existingWithdrawals = lastSnapshotContext.delegatedStakesWithdrawals.getOrElse(
          SortedMap.empty[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
        )

        def isWithdrawalExpired(withdrawalEpoch: EpochProgress): Boolean =
          (withdrawalEpoch |+| withdrawalTimeLimit) <= epochProgress

        for {
          hashedReplacementTokenLocks <- acceptedTokenLocks.filter(_.replaceTokenLockRef.isDefined).traverse(_.toHashed)
          replacementTokenLocks = hashedReplacementTokenLocks.mapFilter(tl => tl.replaceTokenLockRef.tupleRight(tl)).toMap

          existingDelegatedStakes = existingDelegatedStakesNormalized.view
            .mapValues(_.map { record =>
              replacementTokenLocks.get(record.tokenLockRef).fold(record) { hashedTokenLock =>
                record.copy(
                  currentTokenLockRef = hashedTokenLock.hash.some,
                  currentAmount = DelegatedStakeAmount.fromTokenLockAmount(hashedTokenLock.amount).some
                )
              }
            })
            .to(SortedMap)

          (unexpiredWithdrawals, expiredWithdrawals) = existingWithdrawals.foldLeft(
            (
              SortedMap.empty[Address, SortedSet[PendingDelegatedStakeWithdrawal]],
              SortedMap.empty[Address, SortedSet[PendingDelegatedStakeWithdrawal]]
            )
          ) {
            case ((unexpiredAcc, expiredAcc), (address, withdrawals)) =>
              val (expired, unexpired) = withdrawals.partition {
                case PendingDelegatedStakeWithdrawal(_, _, _, withdrawalEpoch, _, _) =>
                  isWithdrawalExpired(withdrawalEpoch)
              }

              val newUnexpired = if (unexpired.nonEmpty) unexpiredAcc + (address -> unexpired) else unexpiredAcc
              val newExpired = if (expired.nonEmpty) expiredAcc + (address -> expired) else expiredAcc

              (newUnexpired, newExpired)
          }
        } yield
          (
            existingDelegatedStakes,
            unexpiredWithdrawals,
            expiredWithdrawals
          )
      }

      def createContext(
        context: GlobalSnapshotInfo,
        lastArtifact: Signed[GlobalIncrementalSnapshot],
        signedArtifact: Signed[GlobalIncrementalSnapshot],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] = for {
        lastActiveTips <- HasherSelector[F].forOrdinal(lastArtifact.ordinal)(implicit hasher => lastArtifact.activeTips)

        lastDeprecatedTips = lastArtifact.tips.deprecated

        blocksForAcceptance = signedArtifact.blocks.toList.map(_.block)
        allowSpendBlocksForAcceptance = signedArtifact.allowSpendBlocks.map(_.toList).getOrElse(List.empty)
        tokenLockBlocksForAcceptance = signedArtifact.tokenLockBlocks.map(_.toList).getOrElse(List.empty)

        scEvents = signedArtifact.stateChannelSnapshots.toList.flatMap {
          case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _)).toList
        }

        unpEventsForAcceptance = signedArtifact.updateNodeParameters
          .getOrElse(SortedMap.empty[Id, Signed[UpdateNodeParameters]])
          .values
          .toList

        cdsEventsForAcceptance = signedArtifact.activeDelegatedStakes
          .getOrElse(SortedMap.empty[Address, List[Signed[UpdateDelegatedStake.Create]]])
          .values
          .toList
          .flatten

        wdsEventsForAcceptance = signedArtifact.delegatedStakesWithdrawals
          .getOrElse(SortedMap.empty[Address, List[Signed[UpdateDelegatedStake.Withdraw]]])
          .values
          .toList
          .flatten

        cncEventsForAcceptance = signedArtifact.activeNodeCollaterals
          .getOrElse(SortedMap.empty[Address, List[Signed[UpdateNodeCollateral.Create]]])
          .values
          .toList
          .flatten

        wncEventsForAcceptance = signedArtifact.nodeCollateralWithdrawals
          .getOrElse(SortedMap.empty[Address, List[Signed[UpdateNodeCollateral.Withdraw]]])
          .values
          .toList
          .flatten

        acceptedTokenLocksFromBlocks = tokenLockBlocksForAcceptance.flatMap(_.tokenLocks.toList)

        delegatedStakeAcceptanceResult <- updateDelegatedStakeAcceptanceManager.accept(
          cdsEventsForAcceptance,
          wdsEventsForAcceptance,
          context,
          signedArtifact.epochProgress,
          signedArtifact.ordinal,
          acceptedTokenLocksFromBlocks
        )

        (
          unexpiredCreateDelegatedStakes,
          unexpiredWithdrawalsDelegatedStaking,
          expiredWithdrawalsDelegatedStaking
        ) <- acceptDelegatedStakes(context, signedArtifact.epochProgress, signedArtifact.ordinal, acceptedTokenLocksFromBlocks)

        updatedCreateDelegatedStakes <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes(
          signedArtifact.delegateRewards.getOrElse(SortedMap.empty),
          delegatedStakeAcceptanceResult,
          PartitionedStakeUpdates(
            unexpiredCreateDelegatedStakes,
            unexpiredWithdrawalsDelegatedStaking,
            expiredWithdrawalsDelegatedStaking
          )
        )

        updatedWithdrawDelegatedStakes <- DelegatedRewardsDistributor.getUpdatedWithdrawalDelegatedStakes(
          context,
          delegatedStakeAcceptanceResult,
          PartitionedStakeUpdates(
            unexpiredCreateDelegatedStakes,
            unexpiredWithdrawalsDelegatedStaking,
            expiredWithdrawalsDelegatedStaking
          )
        )

        (
          acceptanceResult,
          _,
          _,
          _,
          _,
          _,
          returnedSCEvents,
          acceptedRewardTxs,
          snapshotInfo,
          _,
          _,
          _,
          _,
          _
        ) <-
          snapshotAcceptanceManager.accept(
            signedArtifact.ordinal,
            signedArtifact.epochProgress,
            blocksForAcceptance,
            allowSpendBlocksForAcceptance,
            tokenLockBlocksForAcceptance,
            scEvents,
            unpEventsForAcceptance,
            cdsEventsForAcceptance,
            wdsEventsForAcceptance,
            cncEventsForAcceptance,
            wncEventsForAcceptance,
            context,
            lastActiveTips,
            lastDeprecatedTips,
            _ =>
              signedArtifact.rewards
                .pure[F]
                .map { txs =>
                  if (signedArtifact.ordinal.value < tessellation3MigrationStartingOrdinal.value) {
                    DelegatedRewardsResult(
                      delegatorRewardsMap = SortedMap.empty,
                      updatedCreateDelegatedStakes = SortedMap.empty,
                      updatedWithdrawDelegatedStakes = SortedMap.empty,
                      nodeOperatorRewards = txs,
                      reservedAddressRewards = SortedSet.empty,
                      withdrawalRewardTxs = SortedSet.empty,
                      totalEmittedRewardsAmount =
                        Amount(NonNegLong.unsafeFrom(txs.toList.map(_.amount.value.value).distinct.sum)) // mimic incorrect behaviour
                    )
                  } else if (signedArtifact.ordinal.value < setSumFixOrdinal.value) {
                    // Apply same transformation as consensus path for ordinals > incrementalDelegatedStakingStartingOrdinal
                    val transformedCreateDelegatedStakes =
                      if (signedArtifact.ordinal > incrementalDelegatedStakingStartingOrdinal) {
                        updatedCreateDelegatedStakes.view.mapValues { records =>
                          records.map { r =>
                            r.copy(
                              currentTokenLockRef = r.currentTokenLockRef.orElse(r.tokenLockRef.some),
                              currentAmount = r.currentAmount.orElse(r.amount.some)
                            )
                          }
                        }.to(SortedMap)
                      } else updatedCreateDelegatedStakes

                    DelegatedRewardsResult(
                      delegatorRewardsMap = signedArtifact.delegateRewards
                        .getOrElse(SortedMap.empty[PeerId, Map[Address, Amount]]),
                      updatedCreateDelegatedStakes = transformedCreateDelegatedStakes,
                      updatedWithdrawDelegatedStakes = updatedWithdrawDelegatedStakes,
                      nodeOperatorRewards = txs,
                      reservedAddressRewards = SortedSet.empty,
                      withdrawalRewardTxs = SortedSet.empty,
                      totalEmittedRewardsAmount =
                        Amount(NonNegLong.unsafeFrom(txs.toList.map(_.amount.value.value).distinct.sum)) // mimic incorrect behaviour
                    )
                  } else {
                    // Apply same transformation as consensus path for ordinals > incrementalDelegatedStakingStartingOrdinal
                    val transformedCreateDelegatedStakes =
                      if (signedArtifact.ordinal > incrementalDelegatedStakingStartingOrdinal) {
                        updatedCreateDelegatedStakes.view.mapValues { records =>
                          records.map { r =>
                            r.copy(
                              currentTokenLockRef = r.currentTokenLockRef.orElse(r.tokenLockRef.some),
                              currentAmount = r.currentAmount.orElse(r.amount.some)
                            )
                          }
                        }.to(SortedMap)
                      } else updatedCreateDelegatedStakes

                    DelegatedRewardsResult(
                      delegatorRewardsMap = signedArtifact.delegateRewards
                        .getOrElse(SortedMap.empty[PeerId, Map[Address, Amount]]),
                      updatedCreateDelegatedStakes = transformedCreateDelegatedStakes,
                      updatedWithdrawDelegatedStakes = updatedWithdrawDelegatedStakes,
                      nodeOperatorRewards = txs,
                      reservedAddressRewards = SortedSet.empty,
                      withdrawalRewardTxs = SortedSet.empty,
                      totalEmittedRewardsAmount = Amount(NonNegLong.unsafeFrom(txs.toList.map(_.amount.value.value).sum))
                    )
                  }
                },
            StateChannelValidationType.Historical,
            getGlobalSnapshotByOrdinal
          )

        // For followers (currency-l0, dag-l1, currency-l1), we log warnings instead of raising errors
        // for blocks, state channels, and rewards validation divergences.
        // The snapshot was already validated by dag-l0 majority consensus, so local acceptance
        // divergences on followers should not cause permanent stalls. The same rationale applies
        // as for skipping state proof validation below.
        _ <- logger
          .warn(
            s"Follower: ${acceptanceResult.notAccepted.size} blocks not accepted at ordinal=${signedArtifact.ordinal.show}. " +
              s"Reasons: ${acceptanceResult.notAccepted.map { case (_, reason) => reason }.mkString(", ")}. " +
              s"Continuing since snapshot was already validated by L0 majority consensus."
          )
          .whenA(acceptanceResult.notAccepted.nonEmpty)
        _ <- logger
          .warn(
            s"Follower: ${returnedSCEvents.size} state channels returned at ordinal=${signedArtifact.ordinal.show} " +
              s"for addresses: ${returnedSCEvents.toList.map(_.address).mkString(", ")}. " +
              s"Continuing since snapshot was already validated by L0 majority consensus."
          )
          .whenA(returnedSCEvents.nonEmpty)
        diffRewards = acceptedRewardTxs -- signedArtifact.rewards
        _ <- logger
          .warn(
            s"Follower: ${diffRewards.size} rewards not accepted at ordinal=${signedArtifact.ordinal.show}. " +
              s"Continuing since snapshot was already validated by L0 majority consensus."
          )
          .whenA(diffRewards.nonEmpty)
        // State proof validation is also skipped for followers.
        // The snapshot was already validated by the majority consensus on dag-l0.
        // Doing a full MPT sync and validation on every ordinal is too expensive (~2 min for 800K entries).
        // The MPT store is synced once during initial download for query support.

      } yield snapshotInfo
    }

  @derive(eqv, show)
  case class CannotApplyBlocksError(reasons: List[BlockNotAcceptedReason]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot ${reasons.show}"
  }

  @derive(eqv)
  case class CannotApplyStateChannelsError(returnedStateChannels: Set[StateChannelOutput]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot because of returned StateChannels for addresses: ${returnedStateChannels.map(_.address).show}"
  }

  @derive(eqv, show)
  case class CannotApplyRewardsError(notAcceptedRewards: SortedSet[RewardTransaction]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot because of not accepted rewards: ${notAcceptedRewards.show}"
  }
}
