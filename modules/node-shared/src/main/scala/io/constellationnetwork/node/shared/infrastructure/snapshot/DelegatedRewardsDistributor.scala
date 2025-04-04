package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.Sync
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.config.types.{DelegatedRewardsConfig, SharedConfig}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake.DelegatedStakeRecord
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.{DelegatedStakeRewardParameters, RewardFraction, UpdateNodeParameters}
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

trait DelegatedRewardsDistributor[F[_]] {

  /** Calculates the total amount of rewards to mint for the snapshot.
    *
    * @param epochProgress
    *   Current epoch progress
    * @return
    *   Total rewards to mint
    */
  def calculateTotalRewardsToMint(epochProgress: EpochProgress): F[Amount]

  /** Calculates rewards for delegated stakes based on the current epoch and node parameters.
    *
    * @param activeDelegatedStakes
    *   Map of delegated stakes with their accumulated rewards balances
    * @param nodeParametersMap
    *   Map of node parameters containing delegation percentages
    * @param epochProgress
    *   Current epoch progress for determining reward weights
    * @param totalRewards
    *   Total rewards to distribute (needed to calculate the validators' portion)
    * @return
    *   Map of addresses to reward amounts by node ID to be added to their balances
    */
  def calculateDelegatorRewards(
    activeDelegatedStakes: SortedMap[Address, List[DelegatedStakeRecord]],
    nodeParametersMap: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)],
    epochProgress: EpochProgress,
    totalRewards: Amount
  ): F[Map[Address, Map[Id, Amount]]]

  /** Calculates rewards for node operators based on delegator rewards and node parameters. Rewards consist of both static rewards (from
    * validatorsWeight) and dynamic rewards (based on delegator kickback)
    *
    * @param delegatorRewards
    *   Map of delegator rewards by address and node ID
    * @param nodeParametersMap
    *   Map of node parameters containing delegation percentages
    * @param nodesInConsensus
    *   Set of node IDs that participated in consensus for static reward distribution
    * @param epochProgress
    *   Current epoch progress for determining reward weights
    * @param totalRewards
    *   Total rewards to distribute (needed to calculate the validators' portion)
    * @return
    *   Set of reward transactions for node operators (static + dynamic rewards)
    */
  def calculateNodeOperatorRewards(
    delegatorRewards: Map[Address, Map[Id, Amount]],
    nodeParametersMap: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)],
    nodesInConsensus: SortedSet[Id],
    epochProgress: EpochProgress,
    totalRewards: Amount
  ): F[SortedSet[RewardTransaction]]

  /** Calculates reward transactions for withdrawing stakes.
    *
    * @param withdrawingBalances
    *   Map of addresses to their withdrawing balances
    * @return
    *   Set of reward transactions for withdrawals
    */
  def calculateWithdrawalRewardTransactions(
    withdrawingBalances: Map[Address, Amount]
  ): F[SortedSet[RewardTransaction]]
}
