package io.constellationnetwork.node.shared.config

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.schema.SnapshotOrdinal

object DelegatedStakeDoubleWithdrawalOrdinal {
  // This is a consensus boundary: each configured ordinal is the first snapshot that uses
  // the hardened delegated-stake withdrawal and token-unlock behavior. Values must remain
  // stable so historical snapshots replay with the rules that originally produced them.
  private val activationOrdinals: Map[AppEnvironment, SnapshotOrdinal] = Map(
    AppEnvironment.Mainnet -> SnapshotOrdinal.unsafeApply(6710600L),
    AppEnvironment.Testnet -> SnapshotOrdinal.unsafeApply(9999999L),
    AppEnvironment.Integrationnet -> SnapshotOrdinal.unsafeApply(9999999L),
    AppEnvironment.Dev -> SnapshotOrdinal.MinValue
  )

  def get(environment: AppEnvironment): SnapshotOrdinal =
    activationOrdinals(environment)
}
