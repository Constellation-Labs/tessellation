package io.constellationnetwork.schema

import cats.data.NonEmptyList

import scala.collection.immutable.SortedSet

import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.priceOracle._
import io.constellationnetwork.schema.swap.{CurrencyId, SwapAmount}
import io.constellationnetwork.schema.tokenLock.TokenLockAmount
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object artifact {
  @derive(decoder, encoder, order, ordering, show)
  sealed trait SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  case class SpendAction(spendTransactions: NonEmptyList[SpendTransaction]) extends SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  case class SpendTransaction(
    allowSpendRef: Option[Hash],
    currencyId: Option[CurrencyId],
    amount: SwapAmount,
    source: Address,
    destination: Address
  )

  @derive(decoder, encoder, order, ordering, show)
  case class TokenUnlock(
    tokenLockRef: Hash,
    amount: TokenLockAmount,
    currencyId: Option[CurrencyId],
    source: Address
  ) extends SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  case class AllowSpendExpiration(
    allowSpendRef: Hash
  ) extends SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  case class PricingUpdate(price: PriceFraction) extends SharedArtifact {
    def tokenPair: TokenPair = price.tokenPair
  }

  object PricingUpdate {
    val zero = PricingUpdate(PriceFraction(TokenPair.DAG_USD, NonNegFraction.zero))
    val one = PricingUpdate(PriceFraction(TokenPair.DAG_USD, NonNegFraction.one))
  }

  @derive(decoder, encoder, order, ordering, show)
  sealed trait BalanceAdjustmentReason

  case object SpendTransactionNotApplied extends BalanceAdjustmentReason
  case object SpendTransactionSourceNotApplied extends BalanceAdjustmentReason
  case object SpendTransactionDestinationNotApplied extends BalanceAdjustmentReason
  case object TokenUnlockBugDeduction extends BalanceAdjustmentReason

  @derive(decoder, encoder, order, ordering, show)
  case class BalanceAdjustment(
    address: Address,
    reason: BalanceAdjustmentReason,
    reference: SortedSet[Hash],
    increase: Option[Amount],
    deduct: Option[Amount]
  ) extends SharedArtifact

  /** Signed acknowledgment of Global L0 ordinals processed while constructing a currency snapshot.
    *
    * This case class is included in the `artifacts` field of a `CurrencySnapshot` at the moment it is created. It signals that the listed
    * `GlobalIncrementalSnapshot` ordinals have already been consumed for extracting data — such as `SpendAction`s — and should not be
    * reprocessed in the future. Under Currency snapshot protocol 1.0.0, the value is cumulative for the ordinals that GL0 still reports as
    * unapplied: the signed parent carries them forward until GL0 acknowledges them by removing them from `unappliedGlobalChangeOrdinals`.
    *
    * This signed-chain authority replaces the former process-local cache. A JVM restart therefore cannot change whether a spend action is
    * applied or which artifact bytes are emitted.
    *
    * Motivation: Without this mechanism, the same global snapshot data could be reprocessed multiple times across currency snapshots. This
    * would lead to inconsistencies when validating the currency snapshot inside a global snapshot — especially during `SnapshotDiff` checks
    * — where repeated application of the same state transitions (e.g. duplicated spend actions) would cause balance mismatches or invalid
    * diffs.
    *
    * While it currently tracks only ordinals used for `SpendAction` extraction, this design is extensible for future artifact types derived
    * from global snapshots.
    *
    * @param ordinals
    *   A sorted set of processed, still-unacknowledged global snapshot ordinals.
    */
  @derive(decoder, encoder, order, ordering, show)
  case class GlobalSnapshotsProcessed(
    ordinals: SortedSet[SnapshotOrdinal]
  ) extends SharedArtifact
}
