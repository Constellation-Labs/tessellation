package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Applicative
import cats.data.NonEmptySet
import cats.effect.{Async, Sync}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.node.shared.config.types.{DelegatedRewardsConfig, SharedConfig}
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, DelegatedStakeReference, PendingDelegatedStakeWithdrawal}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.{DelegatedStakeRewardParameters, RewardFraction, UpdateNodeParameters}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionAmount}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

/** Result container for delegation rewards calculation
  */
case class DelegationRewardsResult(
  delegatorRewardsMap: SortedMap[Address, Map[PeerId, Amount]],
  updatedCreateDelegatedStakes: SortedMap[Address, List[DelegatedStakeRecord]],
  updatedWithdrawDelegatedStakes: SortedMap[Address, List[PendingDelegatedStakeWithdrawal]],
  nodeOperatorRewards: SortedSet[RewardTransaction],
  reservedAddressRewards: SortedSet[RewardTransaction],
  withdrawalRewardTxs: SortedSet[RewardTransaction],
  totalEmittedRewardsAmount: Amount
)

case class PartitionedStakeUpdates(
  unexpiredCreateDelegatedStakes: SortedMap[Address, List[DelegatedStakeRecord]],
  unexpiredWithdrawalsDelegatedStaking: SortedMap[Address, List[PendingDelegatedStakeWithdrawal]],
  expiredWithdrawalsDelegatedStaking: SortedMap[Address, List[PendingDelegatedStakeWithdrawal]]
)

trait DelegatedRewardsDistributor[F[_]] {

  def calculateTotalRewardsToMint(epochProgress: EpochProgress): F[Amount]

  def distribute(
    lastSnapshotContext: GlobalSnapshotInfo,
    trigger: ConsensusTrigger,
    epochProgress: EpochProgress,
    facilitators: List[(Address, PeerId)],
    delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
    partitionedRecords: PartitionedStakeUpdates
  ): F[DelegationRewardsResult]
}

object DelegatedRewardsDistributor {
  def getUpdatedCreateDelegatedStakes[F[_]: Async: Hasher](
    delegatorRewardsMap: Map[Address, Map[PeerId, Amount]],
    delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
    partitionedRecords: PartitionedStakeUpdates
  ): F[SortedMap[Address, List[DelegatedStakeRecord]]] =
    delegatedStakeDiffs.acceptedCreates.map {
      case (addr, st) =>
        addr -> st.map {
          case (ev, ord) => DelegatedStakeRecord(ev, ord, Balance.empty)
        }
    }.pure[F]
      .map(partitionedRecords.unexpiredCreateDelegatedStakes |+| _)
      .flatMap { activeStakes =>
        // remove withdrawn stakes from the active list
        val withdrawnStakes = delegatedStakeDiffs.acceptedWithdrawals.flatMap(_._2.map(_._1.stakeRef)).toSet
        activeStakes.toList.traverse {
          case (addr, records) =>
            records.traverse { record =>
              DelegatedStakeReference.of(record.event).map(ref => (record, withdrawnStakes(ref.hash)))
            }.map(records => (addr, records.filterNot(_._2).map(_._1)))
        }
      }
      .map(_.map {
        case (addr, recs) =>
          addr -> recs.map {
            case DelegatedStakeRecord(event, ord, bal) =>
              val nodeSpecificReward = delegatorRewardsMap
                .get(addr)
                .flatMap(_.get(event.value.nodeId))
                .getOrElse(Amount.empty)

              val disbursedBalance = bal.plus(nodeSpecificReward).toOption.getOrElse(Balance.empty)

              DelegatedStakeRecord(event, ord, disbursedBalance)
          }
      })
      .map(_.toSortedMap)

  def getUpdatedWithdrawalDelegatedStakes[F[_]: Async: Hasher](
    lastSnapshotContext: GlobalSnapshotInfo,
    delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
    partitionedRecords: PartitionedStakeUpdates
  ): F[SortedMap[Address, List[PendingDelegatedStakeWithdrawal]]] =
    delegatedStakeDiffs.acceptedWithdrawals.toList.traverse {
      case (addr, acceptedWithdrawls) =>
        acceptedWithdrawls.traverse {
          case (ev, ep) =>
            lastSnapshotContext.activeDelegatedStakes
              .flatTraverse(_.get(addr).flatTraverse {
                _.findM { s =>
                  DelegatedStakeReference.of(s.event).map(_.hash === ev.stakeRef)
                }.map(_.map(rec => PendingDelegatedStakeWithdrawal(rec.event, rec.rewards, rec.createdAt, ep)))
              })
              .flatMap(Async[F].fromOption(_, new RuntimeException("Unexpected None when processing user delegations")))
        }.map(addr -> _)
    }.map(SortedMap.from(_))
      .map(partitionedRecords.unexpiredWithdrawalsDelegatedStaking |+| _)

  def sumMintedAmount[F[_]: Async](
    reservedAddressRewards: SortedSet[RewardTransaction],
    nodeOperatorRewards: SortedSet[RewardTransaction],
    delegatorRewardsMap: Map[Address, Map[PeerId, Amount]]
  ): F[Amount] = {
    val reservedEmittedAmount = reservedAddressRewards.map(_.amount.value.value).sum
    val validatorsEmittedAmount = nodeOperatorRewards.map(_.amount.value.value).sum
    val delegatorsEmittedAmount = delegatorRewardsMap.map(_._2.values.map(_.value.value).sum).sum
    val totalEmitted = reservedEmittedAmount + validatorsEmittedAmount + delegatorsEmittedAmount
    NonNegLong
      .from(totalEmitted)
      .bimap(
        new IllegalArgumentException(_),
        Amount(_)
      )
      .pure[F]
      .flatMap(Async[F].fromEither(_))
  }
}
