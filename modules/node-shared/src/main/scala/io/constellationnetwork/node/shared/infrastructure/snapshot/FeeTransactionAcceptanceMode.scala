package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.syntax.order._

import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.schema.SnapshotOrdinal

/** Selects the fee-transaction semantics used while building or recreating a currency snapshot.
  *
  * Live consensus must always use [[Strict]]. The other modes exist only because signed history contains
  * snapshots produced under two older combinations of data-envelope validation and balance arithmetic.
  */
sealed abstract class FeeTransactionAcceptanceMode(
  val validateEveryFeeTransaction: Boolean,
  val applyFeeTransactionsInDataApplication: Boolean,
  val useCheckedCurrencyFeeArithmetic: Boolean
)

object FeeTransactionAcceptanceMode {
  case object LegacyValidationAndUncheckedArithmetic extends FeeTransactionAcceptanceMode(false, false, false)
  case object LegacyValidationAndCheckedArithmetic extends FeeTransactionAcceptanceMode(false, false, true)
  case object Strict extends FeeTransactionAcceptanceMode(true, true, true)

  val live: FeeTransactionAcceptanceMode = Strict

  def historicalRecreationModes(
    lastGlobalSyncView: Option[GlobalSyncView],
    strictValidationStartingOrdinal: SnapshotOrdinal
  ): List[FeeTransactionAcceptanceMode] =
    if (lastGlobalSyncView.exists(_.ordinal >= strictValidationStartingOrdinal)) List(Strict)
    else List(LegacyValidationAndUncheckedArithmetic, LegacyValidationAndCheckedArithmetic, Strict)
}
