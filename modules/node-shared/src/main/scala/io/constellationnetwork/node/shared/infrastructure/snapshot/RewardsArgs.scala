package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.syntax.order._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.transaction.Transaction
import io.constellationnetwork.security.signature.Signed

sealed trait RewardsInput

sealed trait RewardsOutput

case class ClassicRewardsInput(txs: SortedSet[Signed[Transaction]]) extends RewardsInput

case class DelegateRewardsInput(
  udsar: UpdateDelegatedStakeAcceptanceResult,
  psu: PartitionedStakeUpdates,
  ep: EpochProgress,
  ord: SnapshotOrdinal
) extends RewardsInput

case class DelegateRewardsOutput(
  ep: EpochProgress,
  ord: SnapshotOrdinal,
  totalDelegatedAmount: Amount,
  totalRewardPerEpoch: Amount,
  totalDagAmount: Amount,
  currentDagPrice: Amount
) extends RewardsOutput

object DelegateRewardsOutput {
  implicit val delegateRewardsOutputOrdering: Ordering[DelegateRewardsOutput] =
    new Ordering[DelegateRewardsOutput] {
      def compare(x: DelegateRewardsOutput, y: DelegateRewardsOutput): Int = {
        val epochCmp = x.ep.compare(y.ep)
        val ordinalCmp = x.ord.compare(y.ord)
        val result = if (epochCmp != 0) epochCmp else ordinalCmp
        -result
      }
    }
}
