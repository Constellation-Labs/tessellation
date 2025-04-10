package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.Sync
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.node.shared.config.types.{DelegatedRewardsConfig, SharedConfig}
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, PendingWithdrawal}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.{DelegatedStakeRewardParameters, RewardFraction, UpdateNodeParameters}
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionAmount}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

/** Result container for delegation rewards calculation
  */
case class DelegationRewardsResult(
  delegatorRewardsMap: Map[Address, Map[Id, Amount]],
  updatedCreateDelegatedStakes: SortedMap[Address, List[DelegatedStakeRecord]],
  updatedWithdrawDelegatedStakes: SortedMap[Address, List[PendingWithdrawal]],
  nodeOperatorRewards: SortedSet[RewardTransaction],
  withdrawalRewardTxs: SortedSet[RewardTransaction],
  totalEmittedRewardsAmount: Amount
)

case class PartitionedStakeUpdates(
  unexpiredCreateDelegatedStakes: SortedMap[Address, List[DelegatedStakeRecord]],
  expiredCreateDelegatedStakes: SortedMap[Address, List[DelegatedStakeRecord]],
  unexpiredWithdrawalsDelegatedStaking: SortedMap[Address, List[PendingWithdrawal]],
  expiredWithdrawalsDelegatedStaking: SortedMap[Address, List[PendingWithdrawal]]
)

trait DelegatedRewardsDistributor[F[_]] {

  def calculateTotalRewardsToMint(epochProgress: EpochProgress): F[Amount]

  def distribute(
    lastSnapshotContext: GlobalSnapshotInfo,
    trigger: ConsensusTrigger,
    epochProgress: EpochProgress,
    facilitators: NonEmptySet[Id],
    delegatedStakeDiffs: UpdateDelegatedStakeAcceptanceResult,
    partitionedRecords: PartitionedStakeUpdates
  ): F[DelegationRewardsResult]
}
