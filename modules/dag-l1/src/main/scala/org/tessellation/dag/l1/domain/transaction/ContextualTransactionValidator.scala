package org.tessellation.dag.l1.domain.transaction

import cats.data.ValidatedNec
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap

import org.tessellation.dag.l1.domain.transaction.ContextualTransactionValidator.CustomValidationError
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.node.shared.domain.transaction.TransactionValidator.TransactionValidationError
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.transaction.TransactionAmount._
import org.tessellation.schema.transaction._
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

trait CustomContextualTransactionValidator {
  def validate(
    hashedTransaction: Hashed[Transaction],
    context: TransactionValidatorContext
  ): Either[CustomValidationError, Hashed[Transaction]]
}

case class TransactionValidatorContext(
  sourceTransactions: Option[SortedMap[TransactionOrdinal, StoredTransaction]],
  sourceBalance: Balance,
  sourceLastTransactionRef: TransactionReference,
  currentOrdinal: SnapshotOrdinal
)

trait ContextualTransactionValidator {

  import ContextualTransactionValidator._

  def validate(
    hashedTransaction: Hashed[Transaction],
    context: TransactionValidatorContext
  ): ContextualTransactionValidationErrorOr[ValidConflictResolveResult]
}

object ContextualTransactionValidator {

  private val lockedAddresses = Set.empty[Address]

  def make(
    transactionLimitConfig: TransactionLimitConfig,
    customValidator: Option[CustomContextualTransactionValidator]
  ): ContextualTransactionValidator =
    new ContextualTransactionValidator {

      def validate(
        hashedTransaction: Hashed[Transaction],
        context: TransactionValidatorContext
      ): ContextualTransactionValidationErrorOr[ValidConflictResolveResult] =
        validateNotLocked(hashedTransaction)
          .flatMap(validateLastTransactionRef(_, context.sourceTransactions, context.sourceLastTransactionRef))
          .flatMap(validateConflictAtOrdinal(_, context.sourceTransactions))
          .flatMap {
            case (conflictResolveResult, conflictResolvedStoredTxs) =>
              validateBalances(conflictResolveResult.tx, conflictResolvedStoredTxs, context.sourceBalance)
                .flatMap(validateLimit(_, conflictResolvedStoredTxs, context.sourceBalance, context.currentOrdinal))
                .map(_ => conflictResolveResult)
          }
          .flatMap { conflictResolveResult =>
            customValidator match {
              case Some(validator) =>
                validator
                  .validate(hashedTransaction, context)
                  .leftWiden[ContextualTransactionValidationError]
                  .as(conflictResolveResult)
              case None => conflictResolveResult.asRight
            }
          }
          .toValidatedNec

      private def validateBalances(
        transaction: Hashed[Transaction],
        txs: Option[SortedMap[TransactionOrdinal, StoredTransaction]],
        balance: Balance
      ): Either[ContextualTransactionValidationError, Hashed[Transaction]] = {
        val availableBalance = getBalanceAffectedByTxs(txs, balance)
        if (transaction.amount.value <= availableBalance.value)
          transaction.asRight
        else
          InsufficientBalance(transaction.amount, availableBalance).asLeft
      }

      private[transaction] def validateLimit(
        transaction: Hashed[Transaction],
        txs: Option[SortedMap[TransactionOrdinal, StoredTransaction]],
        balance: Balance,
        currentOrdinal: SnapshotOrdinal
      ): Either[ContextualTransactionValidationError, Hashed[Transaction]] = {
        val stored = txs.getOrElse(SortedMap.empty[TransactionOrdinal, StoredTransaction])
        val nonMajorityTransactions = getTransactionsAboveMajority(stored).values.map(_.transaction).toList
        val majorityTxSnapshotOrdinal = getSnapshotOrdinalOfMajorityTx(stored)
        val lastAllowed = calculateLastAllowedTransactionWithinCap(
          transactions = nonMajorityTransactions :+ transaction,
          balance = balance,
          lastMajorityTransactionOrdinal = majorityTxSnapshotOrdinal,
          currentOrdinal = currentOrdinal
        )

        lastAllowed match {
          case Some(allowed) if allowed === transaction => allowed.asRight
          case _                                        => TransactionLimited(TransactionReference.of(transaction), transaction.fee).asLeft
        }
      }

      private def validateConflictAtOrdinal(
        transaction: Hashed[Transaction],
        txs: Option[SortedMap[TransactionOrdinal, StoredTransaction]]
      ): Either[
        ContextualTransactionValidationError,
        (ValidConflictResolveResult, Option[SortedMap[TransactionOrdinal, StoredTransaction]])
      ] =
        txs match {
          case Some(txs) =>
            resolveConflict(transaction, txs) match {
              case noConflict @ NoConflict(_) => (noConflict, txs.some).asRight
              case canOverride @ CanOverride(_) =>
                (canOverride, txs.filterNot { case (ordinal, _) => ordinal > transaction.ordinal }.some).asRight
              case CannotOverride(_, existing, current) => Conflict(transaction.ordinal, existing.hash, current.hash).asLeft
            }
          case None => (NoConflict(transaction), none).asRight
        }

      private def validateLastTransactionRef(
        transaction: Hashed[Transaction],
        txs: Option[SortedMap[TransactionOrdinal, StoredTransaction]],
        lastProcessedTransactionRef: TransactionReference
      ): Either[ContextualTransactionValidationError, Hashed[Transaction]] =
        if (transaction.parent.ordinal < lastProcessedTransactionRef.ordinal)
          ParentOrdinalLowerThenLastProcessedTxOrdinal(transaction.parent.ordinal, lastProcessedTransactionRef.ordinal).asLeft
        else if (transaction.parent === lastProcessedTransactionRef) {
          transaction.asRight
        } else {
          val hasValidParent = txs.exists(_.exists {
            case (parentOrdinal, parentTx) => parentTx.ref === transaction.parent && parentOrdinal > lastProcessedTransactionRef.ordinal
          })

          if (hasValidParent) transaction.asRight else HasNoMatchingParent(transaction.parent.hash).asLeft
        }

      private def validateNotLocked(
        transaction: Hashed[Transaction]
      ): Either[ContextualTransactionValidationError, Hashed[Transaction]] =
        Either
          .cond(
            !lockedAddresses.contains(transaction.source),
            transaction,
            LockedAddressError(transaction.source)
          )

      private def calculateLastAllowedTransactionWithinCap(
        transactions: List[Hashed[Transaction]],
        balance: Balance,
        lastMajorityTransactionOrdinal: SnapshotOrdinal,
        currentOrdinal: SnapshotOrdinal
      ): Option[Hashed[Transaction]] = {
        val ordinalDistanceForBaseBalance = Math
          .floor(transactionLimitConfig.timeToWaitForBaseBalance / transactionLimitConfig.timeTriggerInterval)
          .toLong

        @tailrec
        def loop(
          remainingTransactions: List[Hashed[Transaction]],
          balance: Balance,
          lastTxRefOrdinal: SnapshotOrdinal,
          totalConsumedCap: Double,
          lastAcceptedWithinCap: Option[Hashed[Transaction]]
        ): Option[Hashed[Transaction]] =
          remainingTransactions match {
            case head :: tail =>
              val consumedCap =
                if (head.fee.value >= transactionLimitConfig.minFeeWithoutLimit.value) 0
                else {
                  val distancePassed = currentOrdinal.value - lastTxRefOrdinal.value
                  val transactionsAllowedWithinInterval =
                    if (balance.value > 0) balance.value.toDouble / transactionLimitConfig.baseBalance.value.toDouble else 0.0
                  val distanceBetweenTxsNeeded = ordinalDistanceForBaseBalance.toDouble / transactionsAllowedWithinInterval
                  distanceBetweenTxsNeeded / Math.max(distancePassed, 1)
                }

              val updatedTotalConsumedCap = totalConsumedCap + consumedCap
              val maybeRemainingBalance = toAmount(head.amount).plus(head.fee).flatMap(balance.minus)

              maybeRemainingBalance match {
                case Right(remainingBalance) if updatedTotalConsumedCap <= 1 && remainingBalance.value > 0 && tail.nonEmpty =>
                  loop(
                    remainingTransactions = tail,
                    balance = remainingBalance,
                    lastTxRefOrdinal = currentOrdinal.next,
                    totalConsumedCap = updatedTotalConsumedCap,
                    head.some
                  )
                case Right(_) if updatedTotalConsumedCap <= 1 && tail.isEmpty =>
                  head.some

                case _ => lastAcceptedWithinCap
              }

            case Nil => lastAcceptedWithinCap
          }

        loop(
          remainingTransactions = transactions,
          balance = balance,
          lastTxRefOrdinal = lastMajorityTransactionOrdinal,
          totalConsumedCap = 0.0d,
          lastAcceptedWithinCap = none
        )
      }

      private def getBalanceAffectedByTxs(
        txs: Option[SortedMap[TransactionOrdinal, StoredTransaction]],
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
        transaction: Hashed[Transaction],
        txs: SortedMap[TransactionOrdinal, StoredTransaction]
      ): ConflictResolveResult =
        txs.get(transaction.ordinal) match {
          case Some(WaitingTx(existing)) if existing.fee < transaction.fee => CanOverride(transaction)
          case Some(tx) => CannotOverride(transaction, tx.ref, TransactionReference.of(transaction))
          case None     => NoConflict(transaction)
        }

      private def getSnapshotOrdinalOfMajorityTx(
        txs: SortedMap[TransactionOrdinal, StoredTransaction]
      ): SnapshotOrdinal =
        txs.collectFirst { case (_, MajorityTx(_, snapshotOrdinal)) => snapshotOrdinal }.getOrElse(SnapshotOrdinal.MinValue)

      private def getTransactionsAboveMajority(
        txs: SortedMap[TransactionOrdinal, StoredTransaction]
      ): SortedMap[TransactionOrdinal, NonMajorityTx] =
        txs.collect {
          case (ordinal, stored: NonMajorityTx) => (ordinal, stored)
        }
    }

  @derive(eqv)
  sealed trait ConflictResolveResult
  @derive(eqv)
  sealed trait ValidConflictResolveResult extends ConflictResolveResult {
    val tx: Hashed[Transaction]
  }
  case class NoConflict(tx: Hashed[Transaction]) extends ValidConflictResolveResult
  case class CanOverride(tx: Hashed[Transaction]) extends ValidConflictResolveResult
  case class CannotOverride(tx: Hashed[Transaction], existingRef: TransactionReference, newRef: TransactionReference)
      extends ConflictResolveResult

  @derive(eqv, show)
  sealed trait ContextualTransactionValidationError
  case class ParentOrdinalLowerThenLastProcessedTxOrdinal(parentOrdinal: TransactionOrdinal, lastTxOrdinal: TransactionOrdinal)
      extends ContextualTransactionValidationError
  case class HasNoMatchingParent(parentHash: Hash) extends ContextualTransactionValidationError
  case class Conflict(ordinal: TransactionOrdinal, existingHash: Hash, newHash: Hash) extends ContextualTransactionValidationError
  case class InsufficientBalance(amount: Amount, balance: Balance) extends ContextualTransactionValidationError
  case class TransactionLimited(ref: TransactionReference, fee: TransactionFee) extends ContextualTransactionValidationError
  case class NonContextualValidationError(error: TransactionValidationError) extends ContextualTransactionValidationError
  case class LockedAddressError(address: Address) extends ContextualTransactionValidationError
  case class CustomValidationError(message: String) extends ContextualTransactionValidationError

  type ContextualTransactionValidationErrorOr[A] = ValidatedNec[ContextualTransactionValidationError, A]
}
