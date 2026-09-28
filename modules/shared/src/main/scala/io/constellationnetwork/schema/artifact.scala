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
import io.circe.{Decoder, DecodingFailure, HCursor}

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

  /** Burns only the emitting metagraph's own native currency from its own address. Delegated authorization and DAG currency are
    * deliberately not representable.
    */
  @derive(encoder, order, ordering, show)
  case class BurnTransaction(currencyId: CurrencyId, amount: SwapAmount, source: Address)

  object BurnTransaction {
    implicit val decode: Decoder[BurnTransaction] = Decoder.instance { cursor =>
      strictBurnFields(cursor, Set("currencyId", "amount", "source")).flatMap { _ =>
        for {
          currencyId <- cursor.get[CurrencyId]("currencyId")
          amount <- cursor.get[SwapAmount]("amount")
          source <- cursor.get[Address]("source")
        } yield BurnTransaction(currencyId, amount, source)
      }
    }
  }

  @derive(encoder, order, ordering, show)
  case class BurnAction(burnTransactions: NonEmptyList[BurnTransaction]) extends SharedArtifact

  object BurnAction {
    implicit val decode: Decoder[BurnAction] = Decoder.instance { cursor =>
      strictBurnFields(cursor, Set("burnTransactions"))
        .flatMap(_ => cursor.get[NonEmptyList[BurnTransaction]]("burnTransactions").map(BurnAction(_)))
    }
  }

  private def strictBurnFields(cursor: HCursor, fields: Set[String]): Decoder.Result[Unit] =
    Either.cond(
      cursor.keys.exists(_.toSet == fields),
      (),
      DecodingFailure("Unexpected or missing self-burn fields (delegated burns are unsupported)", cursor.history)
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
  case object FeeTransactionBugDeduction extends BalanceAdjustmentReason

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
