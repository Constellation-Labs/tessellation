package io.constellationnetwork.node.shared.config

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.FieldsAddedOrdinals
import io.constellationnetwork.schema.SnapshotOrdinal

object FieldsAddedOrdinalsFixtures {
  private val active: Map[AppEnvironment, SnapshotOrdinal] = Map(AppEnvironment.Dev -> SnapshotOrdinal.MinValue)

  /** Ordinary tests exercise current behavior. Historical tests must explicitly override the boundary they exercise. */
  val current: FieldsAddedOrdinals = FieldsAddedOrdinals(
    tessellation3Migration = active,
    tessellation301Migration = active,
    checkSyncGlobalSnapshotField = active,
    metagraphSyncData = active,
    updatedLastSyncGlobalOrder = active,
    updatedLastSyncGlobalFromPeersInConsensus = active,
    updatingCombineFunctionSpendActions = active,
    fixingAllowSpendExpiration = active,
    fixingAllowSpendAndTokenLockValidation = active,
    fixingFeeTransactionBalanceOverflow = active,
    fixingDataApplicationFeeValidation = active,
    fixingAllowSpendDestinationCredit = active,
    preventingAllowSpendResurrection = active,
    fixingGlobalAllowSpendExpiration = active,
    fixingSpendActionAggregateBalance = active,
    removingProcessedDelegatedStakeWithdrawals = active,
    tessellation41Migration = active
  )
}
