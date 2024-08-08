package org.tessellation.dag.l1.domain.transaction

import cats.data.ValidatedNec
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap

import org.tessellation.dag.l1.domain.transaction.ContextualAllowSpendValidator.CustomValidationError
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.node.shared.domain.transaction.AllowSpendValidator.AllowSpendTransactionValidationError
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.swap._
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

import StoredAllowSpend.{MajorityAllowSpend, NonMajorityAllowSpend, WaitingAllowSpend}

trait CustomContextualAllowSpendValidator {
  def validate(
    hashedTransaction: Hashed[AllowSpend],
    context: AllowSpendValidatorContext
  ): Either[CustomValidationError, Hashed[AllowSpend]]
}

case class AllowSpendValidatorContext(
  sourceTransactions: Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]],
  sourceBalance: Balance,
  sourceLastTransactionRef: AllowSpendReference,
  currentOrdinal: SnapshotOrdinal
)

trait ContextualAllowSpendValidator {

  import ContextualAllowSpendValidator._

  def validate(
    hashedTransaction: Hashed[AllowSpend],
    context: AllowSpendValidatorContext
  ): ContextualAllowSpendValidationErrorOr[ValidConflictResolveResult]
}

object ContextualAllowSpendValidator {

  private val lockedAddresses = Set(
    Address("DAG6LvxLSdWoC9uJZPgXtcmkcWBaGYypF6smaPyH") // NOTE: BitForex
  )

  def make(
    transactionLimitConfig: TransactionLimitConfig,
    customValidator: Option[CustomContextualAllowSpendValidator]
  ): ContextualAllowSpendValidator =
    new ContextualAllowSpendValidator {

      def validate(
        hashedTransaction: Hashed[AllowSpend],
        context: AllowSpendValidatorContext
      ): ContextualAllowSpendValidationErrorOr[ValidConflictResolveResult] =
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
                  .leftWiden[ContextualAllowSpendValidationError]
                  .as(conflictResolveResult)
              case None => conflictResolveResult.asRight
            }
          }
          .toValidatedNec

      private def validateBalances(
        transaction: Hashed[AllowSpend],
        txs: Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]],
        balance: Balance
      ): Either[ContextualAllowSpendValidationError, Hashed[AllowSpend]] = {
        val availableBalance = getBalanceAffectedByTxs(txs, balance)
        if (transaction.amount.value <= availableBalance.value)
          transaction.asRight
        else
          InsufficientBalance(transaction.amount, availableBalance).asLeft
      }

      private[transaction] def validateLimit(
        transaction: Hashed[AllowSpend],
        txs: Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]],
        balance: Balance,
        currentOrdinal: SnapshotOrdinal
      ): Either[ContextualAllowSpendValidationError, Hashed[AllowSpend]] = {
        val stored = txs.getOrElse(SortedMap.empty[AllowSpendOrdinal, StoredAllowSpend])
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
          case _                                        => TransactionLimited(AllowSpendReference.of(transaction), transaction.fee).asLeft
        }
      }

      private def validateConflictAtOrdinal(
        transaction: Hashed[AllowSpend],
        txs: Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]]
      ): Either[
        ContextualAllowSpendValidationError,
        (ValidConflictResolveResult, Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]])
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
        transaction: Hashed[AllowSpend],
        txs: Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]],
        lastProcessedTransactionRef: AllowSpendReference
      ): Either[ContextualAllowSpendValidationError, Hashed[AllowSpend]] =
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
        transaction: Hashed[AllowSpend]
      ): Either[ContextualAllowSpendValidationError, Hashed[AllowSpend]] =
        Either
          .cond(
            !lockedAddresses.contains(transaction.source),
            transaction,
            LockedAddressError(transaction.source)
          )

      private def calculateLastAllowedTransactionWithinCap(
        transactions: List[Hashed[AllowSpend]],
        balance: Balance,
        lastMajorityTransactionOrdinal: SnapshotOrdinal,
        currentOrdinal: SnapshotOrdinal
      ): Option[Hashed[AllowSpend]] = {
        val ordinalDistanceForBaseBalance = Math
          .floor(transactionLimitConfig.timeToWaitForBaseBalance / transactionLimitConfig.timeTriggerInterval)
          .toLong

        @tailrec
        def loop(
          remainingTransactions: List[Hashed[AllowSpend]],
          balance: Balance,
          lastTxRefOrdinal: SnapshotOrdinal,
          totalConsumedCap: Double,
          lastAcceptedWithinCap: Option[Hashed[AllowSpend]]
        ): Option[Hashed[AllowSpend]] =
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
              val maybeRemainingBalance = SwapAmount.toAmount(head.amount).plus(head.fee).flatMap(balance.minus)

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
        txs: Option[SortedMap[AllowSpendOrdinal, StoredAllowSpend]],
        latestBalance: Balance
      ): Balance =
        txs match {
          case Some(txs) =>
            getTransactionsAboveMajority(txs)
              .foldLeftM(latestBalance) {
                case (acc, curr) =>
                  SwapAmount.toAmount(curr.transaction.amount).plus(curr.transaction.fee).flatMap(acc.minus)
              }
              .getOrElse(Balance.empty)
          case None => latestBalance
        }

      private def resolveConflict(
        transaction: Hashed[AllowSpend],
        txs: SortedMap[AllowSpendOrdinal, StoredAllowSpend]
      ): ConflictResolveResult =
        txs.get(transaction.ordinal) match {
          case Some(WaitingAllowSpend(existing)) if existing.fee < transaction.fee => CanOverride(transaction)
          case Some(tx) => CannotOverride(transaction, tx.ref, AllowSpendReference.of(transaction))
          case None     => NoConflict(transaction)
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
  case class TransactionLimited(ref: AllowSpendReference, fee: AllowSpendFee) extends ContextualAllowSpendValidationError
  case class NonContextualValidationError(error: AllowSpendTransactionValidationError) extends ContextualAllowSpendValidationError
  case class LockedAddressError(address: Address) extends ContextualAllowSpendValidationError
  case class CustomValidationError(message: String) extends ContextualAllowSpendValidationError

  type ContextualAllowSpendValidationErrorOr[A] = ValidatedNec[ContextualAllowSpendValidationError, A]
}
