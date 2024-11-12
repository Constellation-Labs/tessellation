package io.constellationnetwork.node.shared.domain.swap

import cats.data.ValidatedNec
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.config.types.AllowSpendsConfig
import io.constellationnetwork.node.shared.domain.swap.AllowSpendValidator.AllowSpendValidationError
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.SwapAmount._
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

import ContextualAllowSpendValidator.CustomValidationError

trait CustomContextualAllowSpendValidator {
  def validate(
    hashedAllowSpend: Hashed[AllowSpend],
    context: AllowSpendValidatorContext
  ): Either[CustomValidationError, Hashed[AllowSpend]]
}

case class AllowSpendValidatorContext(
  sourceAllowSpends: Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]],
  sourceBalance: Balance,
  sourceLastAllowSpendRef: AllowSpendReference,
  currentOrdinal: SnapshotOrdinal,
  currentEpochProgress: EpochProgress
)

trait ContextualAllowSpendValidator {

  import ContextualAllowSpendValidator._

  def validate(
    hashedAllowSpend: Hashed[AllowSpend],
    context: AllowSpendValidatorContext
  ): ContextualAllowSpendValidationErrorOr[ValidConflictResolveResult]
}

object ContextualAllowSpendValidator {

  def make(
    customValidator: Option[CustomContextualAllowSpendValidator],
    allowSpendsConfig: AllowSpendsConfig
  ): ContextualAllowSpendValidator =
    new ContextualAllowSpendValidator {

      def validate(
        hashedAllowSpend: Hashed[AllowSpend],
        context: AllowSpendValidatorContext
      ): ContextualAllowSpendValidationErrorOr[ValidConflictResolveResult] =
        validateLastAllowSpendRef(hashedAllowSpend, context.sourceAllowSpends, context.sourceLastAllowSpendRef)
          .flatMap(validateEpochProgress(_, context.currentEpochProgress))
          .flatMap(validateConflictAtOrdinal(_, context.sourceAllowSpends))
          .flatMap {
            case (conflictResolveResult, conflictResolvedStoredTxs) =>
              validateBalances(conflictResolveResult.tx, conflictResolvedStoredTxs, context.sourceBalance)
                .map(_ => conflictResolveResult)
          }
          .flatMap { conflictResolveResult =>
            customValidator match {
              case Some(validator) =>
                validator
                  .validate(hashedAllowSpend, context)
                  .leftWiden[ContextualAllowSpendValidationError]
                  .as(conflictResolveResult)
              case None => conflictResolveResult.asRight
            }
          }
          .toValidatedNec

      private def validateBalances(
        allowSpend: Hashed[AllowSpend],
        txs: Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]],
        balance: Balance
      ): Either[ContextualAllowSpendValidationError, Hashed[AllowSpend]] = {
        val availableBalance = getBalanceAffectedByTxs(txs, balance)
        if (allowSpend.amount.value <= availableBalance.value)
          allowSpend.asRight
        else
          InsufficientBalance(allowSpend.amount, availableBalance).asLeft
      }

      private def validateEpochProgress(
        allowSpend: Hashed[AllowSpend],
        currentEpochProgress: EpochProgress
      ): Either[ContextualAllowSpendValidationError, Hashed[AllowSpend]] = {
        val low =
          allowSpend.lastValidEpochProgress >= (currentEpochProgress |+| EpochProgress(allowSpendsConfig.lastValidEpochProgress.min))
        val high =
          allowSpend.lastValidEpochProgress <= (currentEpochProgress |+| EpochProgress(allowSpendsConfig.lastValidEpochProgress.max))

        if (low && high) {
          allowSpend.asRight
        } else
          TooFarLastValidEpochProgress(allowSpend.lastValidEpochProgress).asLeft
      }

      private def validateConflictAtOrdinal(
        allowSpend: Hashed[AllowSpend],
        txs: Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]]
      ): Either[
        ContextualAllowSpendValidationError,
        (ValidConflictResolveResult, Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]])
      ] =
        txs match {
          case Some(txs) =>
            resolveConflict(allowSpend, txs) match {
              case noConflict @ NoConflict(_) => (noConflict, txs.some).asRight
              case canOverride @ CanOverride(_) =>
                (canOverride, txs.filterNot { case (ordinal, _) => ordinal > allowSpend.ordinal }.some).asRight
              case CannotOverride(_, existing, current) => Conflict(allowSpend.ordinal, existing.hash, current.hash).asLeft
            }
          case None => (NoConflict(allowSpend), none).asRight
        }

      private def validateLastAllowSpendRef(
        allowSpend: Hashed[AllowSpend],
        txs: Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]],
        lastProcessedAllowSpendRef: AllowSpendReference
      ): Either[ContextualAllowSpendValidationError, Hashed[AllowSpend]] =
        if (allowSpend.parent.ordinal < lastProcessedAllowSpendRef.ordinal)
          ParentOrdinalLowerThenLastProcessedTxOrdinal(allowSpend.parent.ordinal, lastProcessedAllowSpendRef.ordinal).asLeft
        else if (allowSpend.parent === lastProcessedAllowSpendRef) {
          allowSpend.asRight
        } else {
          val hasValidParent = txs.exists(_.exists {
            case (parentOrdinal, parentTx) => parentTx.ref === allowSpend.parent && parentOrdinal > lastProcessedAllowSpendRef.ordinal
          })

          if (hasValidParent) allowSpend.asRight else HasNoMatchingParent(allowSpend.parent.hash).asLeft
        }

      private def getBalanceAffectedByTxs(
        txs: Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]],
        latestBalance: Balance
      ): Balance =
        txs match {
          case Some(txs) =>
            getTransactionsAboveMajority(txs)
              .foldLeftM(latestBalance) {
                case (acc, curr) =>
                  toAmount(curr.transaction.amount).plus(curr.transaction.fee).flatMap(acc.minus)
              }
              .getOrElse(Balance.empty)
          case None => latestBalance
        }

      private def resolveConflict(
        allowSpend: Hashed[AllowSpend],
        txs: SortedMap[AllowSpendOrdinal, StoredAllowSpend]
      ): ConflictResolveResult =
        txs.get(allowSpend.ordinal) match {
          case Some(WaitingAllowSpend(existing)) if existing.fee < allowSpend.fee => CanOverride(allowSpend)
          case Some(tx) => CannotOverride(allowSpend, tx.ref, AllowSpendReference.of(allowSpend))
          case None     => NoConflict(allowSpend)
        }

      private def getSnapshotOrdinalOfMajorityTx(
        txs: SortedMap[AllowSpendOrdinal, StoredAllowSpend]
      ): SnapshotOrdinal =
        txs.collectFirst { case (_, MajorityAllowSpend(_, snapshotOrdinal)) => snapshotOrdinal }.getOrElse(SnapshotOrdinal.MinValue)

      private def getTransactionsAboveMajority(
        txs: SortedMap[AllowSpendOrdinal, StoredAllowSpend]
      ): SortedMap[AllowSpendOrdinal, NonMajorityAllowSpend] =
        txs.collect {
          case (ordinal, stored: NonMajorityAllowSpend) => (ordinal, stored)
        }
    }

  @derive(eqv)
  sealed trait ConflictResolveResult
  @derive(eqv)
  sealed trait ValidConflictResolveResult extends ConflictResolveResult {
    val tx: Hashed[AllowSpend]
  }
  case class NoConflict(tx: Hashed[AllowSpend]) extends ValidConflictResolveResult
  case class CanOverride(tx: Hashed[AllowSpend]) extends ValidConflictResolveResult
  case class CannotOverride(tx: Hashed[AllowSpend], existingRef: AllowSpendReference, newRef: AllowSpendReference)
      extends ConflictResolveResult

  @derive(eqv, show)
  sealed trait ContextualAllowSpendValidationError
  case class ParentOrdinalLowerThenLastProcessedTxOrdinal(parentOrdinal: AllowSpendOrdinal, lastTxOrdinal: AllowSpendOrdinal)
      extends ContextualAllowSpendValidationError
  case class HasNoMatchingParent(parentHash: Hash) extends ContextualAllowSpendValidationError
  case class Conflict(ordinal: AllowSpendOrdinal, existingHash: Hash, newHash: Hash) extends ContextualAllowSpendValidationError
  case class InsufficientBalance(amount: Amount, balance: Balance) extends ContextualAllowSpendValidationError
  case class AllowSpendLimited(ref: AllowSpendReference, fee: AllowSpendFee) extends ContextualAllowSpendValidationError
  case class NonContextualValidationError(error: AllowSpendValidationError) extends ContextualAllowSpendValidationError
  case class LockedAddressError(address: Address) extends ContextualAllowSpendValidationError
  case class TooFarLastValidEpochProgress(epochProgress: EpochProgress) extends ContextualAllowSpendValidationError
  case class CustomValidationError(message: String) extends ContextualAllowSpendValidationError

  type ContextualAllowSpendValidationErrorOr[A] = ValidatedNec[ContextualAllowSpendValidationError, A]
}
