package io.constellationnetwork.node.shared.infrastructure.snapshot

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.transaction.Transaction
import io.constellationnetwork.security.signature.Signed

sealed trait RewardsInput

case class ClassicRewardsInput(txs: SortedSet[Signed[Transaction]]) extends RewardsInput
case class DelegateRewardsInput(udsar: UpdateDelegatedStakeAcceptanceResult, psu: PartitionedStakeUpdates, ep: EpochProgress)
    extends RewardsInput
