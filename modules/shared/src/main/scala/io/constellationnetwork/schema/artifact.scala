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

  /** Temporary artifact used to indicate which global snapshot ordinals have already been processed during the construction of a currency
    * snapshot.
    *
    * This case class is included in the `artifacts` field of a `CurrencySnapshot` at the moment it is created. It signals that the listed
    * `GlobalIncrementalSnapshot` ordinals have already been consumed for extracting data — such as `SpendAction`s — and should not be
    * reprocessed in the future.
    *
    * ⚠️ Note: This artifact is not persisted long-term. It is only populated during snapshot creation, and once the currency snapshot is
    * finalized, this information is discarded.
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
    *   A sorted set of global snapshot ordinals that were processed in the current currency snapshot.
    */
  @derive(decoder, encoder, order, ordering, show)
  case class GlobalSnapshotsProcessed(
    ordinals: SortedSet[SnapshotOrdinal]
  ) extends SharedArtifact
}
