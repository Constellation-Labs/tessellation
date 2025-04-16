package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Parallel
import cats.data.Validated
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace
import io.constellationnetwork.merkletree.StateProofValidator
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.snapshot.SnapshotContextFunctions
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, UpdateDelegatedStake}
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.schema.transaction.RewardTransaction
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelValidationType}
import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.types.all.NonNegLong
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceManager
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersAcceptanceManager
import io.constellationnetwork.node.shared.domain.nodeCollateral.UpdateNodeCollateralAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.rewards.DelegatedRewardsDistributor.getUpdatedCreateDelegatedStakes

abstract class GlobalSnapshotContextFunctions[F[_]] extends SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]

object GlobalSnapshotContextFunctions {
  def make[F[_]: Async: Parallel: HasherSelector](
    snapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[F],
    updateNodeParametersAcceptanceManager: UpdateNodeParametersAcceptanceManager[F],
    updateDelegatedStakeAcceptanceManager: UpdateDelegatedStakeAcceptanceManager[F],
    updateNodeCollateralAcceptanceManager: UpdateNodeCollateralAcceptanceManager[F]
  ) =
    new GlobalSnapshotContextFunctions[F] {
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

        updatedDelegatedStakes <- getUpdatedCreateDelegatedStakes(
          delegatedStakeAcceptanceResult
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
                .map(txs =>
                  DelegationRewardsResult(
                    delegatorRewardsMap = Map.empty,
                    updatedCreateDelegatedStakes = signedArtifact.activeDelegatedStakes
                      .map(_.map(_._2.map(_.value)))
                      .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]]),
                    updatedWithdrawDelegatedStakes = signedArtifact.delegatedStakesWithdrawals,
                    nodeOperatorRewards = txs,
                    reservedAddressRewards = SortedSet.empty,
                    withdrawalRewardTxs = SortedSet.empty,
                    totalEmittedRewardsAmount = Amount(NonNegLong.unsafeFrom(txs.map(_.amount.value.value).sum))
                  )
                ),
            StateChannelValidationType.Historical,
            lastGlobalSnapshots,
            getGlobalSnapshotByOrdinal
          )

//        val classicRewardsFn =               classicRewards
//          .distribute(_, _, _, _, _)
//          .map { rewardTxs =>
//            DelegationRewardsResult(
//              delegatorRewardsMap = Map.empty,
//              updatedCreateDelegatedStakes = SortedMap.empty,
//              updatedWithdrawDelegatedStakes = SortedMap.empty,
//              nodeOperatorRewards = rewardTxs,
//              reservedAddressRewards = SortedSet.empty,
//              withdrawalRewardTxs = SortedSet.empty,
//              totalEmittedRewardsAmount = Amount(NonNegLong.unsafeFrom(rewardTxs.map(_.amount.value.value).sum))
//            )
//          }
//
//        val rewardsWithFacilitators: List[(Address, Id)] => RewardsInput => F[DelegationRewardsResult] = { faciltators: List[(Address, Id)] =>
//      {
//        case ClassicRewardsInput(txs) =>
//        classicRewardsFn(lastArtifact, snapshotContext.balances, txs, trigger, events)
//
//        case DelegateRewardsInput(udsar, psu, ep) =>
//        if (shouldUseDelegatedRewards(lastArtifact.ordinal.next, ep)) {
//        delegatedRewards.distribute(snapshotContext, trigger, ep, faciltators, udsar, psu)
//      } else {
//      classicRewardsFn(lastArtifact, snapshotContext.balances, SortedSet.empty, trigger, events)
//      }
//      }
//      }

//        DelegationRewardsResult(
//        _,
//        updatedCreateDelegatedStakes,
//        updatedWithdrawDelegatedStakes,
//        nodeOperatorRewards,
//        reservedAddressRewards,
//        withdrawalRewardTxs,
//        _
//        ) <-
//          if (ordinal.value < tessellation3MigrationStartingOrdinal.value) {
//            calculateRewardsFn(ClassicRewardsInput(acceptedTransactions))
//          } else {
//            val acceptedTokenLockRefs = delegatedStakeAcceptanceResult.acceptedCreates.map {
//              case (addr, creates) => (addr, creates.map(_._1.tokenLockRef).toSet)
//            }
//            val filteredUnexpiredCreateDelegatedStakes = unexpiredCreateDelegatedStakes.map {
//              case (addr, recs) =>
//                val tokenLocks = acceptedTokenLockRefs.getOrElse(addr, Set.empty)
//                (addr, recs.filterNot(record => tokenLocks(record.event.tokenLockRef)))
//            }
//            calculateRewardsFn(
//              DelegateRewardsInput(
//                delegatedStakeAcceptanceResult,
//                PartitionedStakeUpdates(
//                  filteredUnexpiredCreateDelegatedStakes,
//                  expiredCreateDelegatedStakes,
//                  unexpiredWithdrawalsDelegatedStaking,
//                  expiredWithdrawalsDelegatedStaking
//                ),
//                epochProgress
//              )
//            )
//          }
//
//        (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
//          updatedGlobalBalances ++ currencyAcceptanceBalanceUpdate,
//          withdrawalRewardTxs ++ nodeOperatorRewards ++ reservedAddressRewards
//        )

//        // Calculate output values that will be integrated into consensus state / snapshot
//        updatedCreateDelegatedStakes <-
//          delegatedStakeDiffs.acceptedCreates.map {
//              case (addr, st) =>
//                addr -> st.map {
//                  case (ev, ord) => DelegatedStakeRecord(ev, ord, Balance.empty, Amount(NonNegLong.unsafeFrom(0L)))
//                }
//            }.pure[F]
//            .map(partitionedRecords.unexpiredCreateDelegatedStakes |+| _)
//            .map(_.map {
//              case (addr, recs) =>
//                addr -> recs.map {
//                  case DelegatedStakeRecord(event, ord, bal, _) =>
//                    val nodeSpecificReward = delegatorRewardsMap
//                      .get(addr)
//                      .flatMap(_.get(event.value.nodeId.toId))
//                      .getOrElse(Amount.empty)
//
//                    val disbursedBalance = bal.plus(nodeSpecificReward).toOption.getOrElse(Balance.empty)
//
//                    DelegatedStakeRecord(event, ord, disbursedBalance, nodeSpecificReward)
//                }
//            })
//
//        updatedWithdrawDelegatedStakes <-
//          delegatedStakeDiffs.acceptedWithdrawals.toList.traverse {
//            case (addr, acceptedWithdrawls) =>
//              acceptedWithdrawls.traverse {
//                case (ev, ep) =>
//                  lastSnapshotContext.activeDelegatedStakes
//                    .flatTraverse(_.get(addr).flatTraverse {
//                      _.findM { s =>
//                        DelegatedStakeReference.of(s.event).map(_.hash === ev.stakeRef)
//                      }.map(_.map(rec => PendingWithdrawal(ev, rec.rewards, ep)))
//                    })
//                    .flatMap(Async[F].fromOption(_, new RuntimeException("Unexpected None when processing user delegations")))
//              }.map(addr -> _)
//          }.map(_.toSortedMap).map(partitionedRecords.unexpiredWithdrawalsDelegatedStaking |+| _)

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
