package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Parallel
import cats.data.Validated
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.merkletree.StateProofValidator
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceManager
import io.constellationnetwork.node.shared.domain.snapshot.SnapshotContextFunctions
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.TimeTrigger
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.schema.transaction.RewardTransaction
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelValidationType}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.types.all.NonNegLong

abstract class GlobalSnapshotContextFunctions[F[_]] extends SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]

object GlobalSnapshotContextFunctions {
  def make[F[_]: Async: Parallel: HasherSelector: SecurityProvider](
    snapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[F],
    updateDelegatedStakeAcceptanceManager: UpdateDelegatedStakeAcceptanceManager[F],
    withdrawalTimeLimit: EpochProgress,
    tessellation3MigrationStartingOrdinal: SnapshotOrdinal
  ) =
    new GlobalSnapshotContextFunctions[F] {

      // todo - avoid duplication of this function here and in GlobalSnapshotConsensusFunctions
      private def acceptDelegatedStakes(
        lastSnapshotContext: GlobalSnapshotInfo,
        epochProgress: EpochProgress
      )(implicit h: Hasher[F]): (
        SortedMap[Address, List[DelegatedStakeRecord]],
        SortedMap[Address, List[PendingWithdrawal]],
        SortedMap[Address, List[PendingWithdrawal]]
      ) = {
        val existingDelegatedStakes = lastSnapshotContext.activeDelegatedStakes.getOrElse(
          SortedMap.empty[Address, List[DelegatedStakeRecord]]
        )

        val existingWithdrawals = lastSnapshotContext.delegatedStakesWithdrawals.getOrElse(
          SortedMap.empty[Address, List[PendingWithdrawal]]
        )

        def isWithdrawalExpired(withdrawalEpoch: EpochProgress): Boolean =
          (withdrawalEpoch |+| withdrawalTimeLimit) <= epochProgress

        val unexpiredWithdrawals = existingWithdrawals.map {
          case (address, withdrawals) =>
            address -> withdrawals.filterNot {
              case PendingWithdrawal(_, _, withdrawalEpoch) =>
                isWithdrawalExpired(withdrawalEpoch)
            }
        }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

        val expiredWithdrawals = existingWithdrawals.map {
          case (address, withdrawals) =>
            address -> withdrawals.filter {
              case PendingWithdrawal(_, _, withdrawalEpoch) =>
                isWithdrawalExpired(withdrawalEpoch)
            }
        }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

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
        lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
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

        delegatedStakeAcceptanceResult <- updateDelegatedStakeAcceptanceManager.accept(
          cdsEventsForAcceptance,
          wdsEventsForAcceptance,
          context,
          signedArtifact.epochProgress,
          signedArtifact.ordinal
        )

        (
          unexpiredCreateDelegatedStakes,
          unexpiredWithdrawalsDelegatedStaking,
          expiredWithdrawalsDelegatedStaking
        ) = acceptDelegatedStakes(context, signedArtifact.epochProgress)

        filteredUnexpiredCreateDelegatedStakes = unexpiredCreateDelegatedStakes.map {
          case (addr, recs) =>
            val tokenLocks = delegatedStakeAcceptanceResult.acceptedCreates.map {
              case (addr, creates) => (addr, creates.map(_._1.tokenLockRef).toSet)
            }.getOrElse(addr, Set.empty)
            (addr, recs.filterNot(record => tokenLocks(record.event.tokenLockRef)))
        }

        updatedCreateDelegatedStakes <- DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes(
          signedArtifact.delegateRewards.getOrElse(SortedMap.empty),
          delegatedStakeAcceptanceResult,
          PartitionedStakeUpdates(
            filteredUnexpiredCreateDelegatedStakes,
            unexpiredWithdrawalsDelegatedStaking,
            expiredWithdrawalsDelegatedStaking
          )
        )

        updatedWithdrawDelegatedStakes <- DelegatedRewardsDistributor.getUpdatedWithdrawalDelegatedStakes(
          context,
          delegatedStakeAcceptanceResult,
          PartitionedStakeUpdates(
            filteredUnexpiredCreateDelegatedStakes,
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
                    DelegationRewardsResult(
                      delegatorRewardsMap = SortedMap.empty,
                      updatedCreateDelegatedStakes = SortedMap.empty,
                      updatedWithdrawDelegatedStakes = SortedMap.empty,
                      nodeOperatorRewards = txs,
                      reservedAddressRewards = SortedSet.empty,
                      withdrawalRewardTxs = SortedSet.empty,
                      totalEmittedRewardsAmount = Amount(NonNegLong.unsafeFrom(txs.map(_.amount.value.value).sum))
                    )
                  } else {
                    DelegationRewardsResult(
                      delegatorRewardsMap = signedArtifact.delegateRewards.getOrElse(SortedMap.empty),
                      updatedCreateDelegatedStakes = updatedCreateDelegatedStakes,
                      updatedWithdrawDelegatedStakes = updatedWithdrawDelegatedStakes,
                      nodeOperatorRewards = txs,
                      reservedAddressRewards = SortedSet.empty,
                      withdrawalRewardTxs = SortedSet.empty,
                      totalEmittedRewardsAmount = Amount(NonNegLong.unsafeFrom(txs.map(_.amount.value.value).sum))
                    )
                  }
                },
            StateChannelValidationType.Historical,
            lastGlobalSnapshots,
            getGlobalSnapshotByOrdinal
          )

        _ <- CannotApplyBlocksError(acceptanceResult.notAccepted.map { case (_, reason) => reason })
          .raiseError[F, Unit]
          .whenA(acceptanceResult.notAccepted.nonEmpty)
        _ <- CannotApplyStateChannelsError(returnedSCEvents).raiseError[F, Unit].whenA(returnedSCEvents.nonEmpty)
        diffRewards = acceptedRewardTxs -- signedArtifact.rewards
        _ <- CannotApplyRewardsError(diffRewards).raiseError[F, Unit].whenA(diffRewards.nonEmpty)
        hashedArtifact <- HasherSelector[F].forOrdinal(signedArtifact.ordinal)(implicit hasher => signedArtifact.toHashed)
        calculatedStateProof <- HasherSelector[F].forOrdinal(signedArtifact.ordinal) { implicit hasher =>
          hasher.getLogic(signedArtifact.ordinal) match {
            case JsonHash => snapshotInfo.stateProof(signedArtifact.ordinal)
            case KryoHash => GlobalSnapshotInfoV2.fromGlobalSnapshotInfo(snapshotInfo).stateProof(signedArtifact.ordinal)
          }
        }
        validation <- StateProofValidator.validate(hashedArtifact, calculatedStateProof)
        _ = validation match {
          case Validated.Valid(_)   => Async[F].unit
          case Validated.Invalid(e) => e.raiseError[F, Unit]
        }

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
