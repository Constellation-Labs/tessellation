package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.syntax.order._

import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.schema.SnapshotOrdinal

/** Selects the allow-spend balance semantics used while building or recreating a snapshot.
  *
  * Live consensus must always use the Escrow mode. LegacyCreditDestination exists only to reproduce signed historical snapshots created
  * before allow-spends were correctly treated as escrows.
  */
sealed abstract class AllowSpendBlockAcceptanceMode(val creditDestination: Boolean)

object AllowSpendBlockAcceptanceMode {
  private[snapshot] case object LegacyCreditDestination extends AllowSpendBlockAcceptanceMode(true)
  case object Escrow extends AllowSpendBlockAcceptanceMode(false)

  val live: AllowSpendBlockAcceptanceMode = Escrow

  def currencyHistoricalRecreationModes(
    lastGlobalSyncView: Option[GlobalSyncView],
    escrowStartingOrdinal: SnapshotOrdinal
  ): List[AllowSpendBlockAcceptanceMode] =
    if (lastGlobalSyncView.exists(_.ordinal >= escrowStartingOrdinal)) List(Escrow)
    else List(Escrow, LegacyCreditDestination)

  def globalHistoricalRecreationModes(
    snapshotOrdinal: SnapshotOrdinal,
    escrowStartingOrdinal: SnapshotOrdinal
  ): List[AllowSpendBlockAcceptanceMode] =
    if (snapshotOrdinal >= escrowStartingOrdinal) List(Escrow)
    else List(Escrow, LegacyCreditDestination)
}
