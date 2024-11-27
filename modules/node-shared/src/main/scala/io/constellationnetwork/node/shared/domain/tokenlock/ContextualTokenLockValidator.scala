package io.constellationnetwork.node.shared.domain.tokenlock

import cats.data.ValidatedNec
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.config.types.TokenLocksConfig
import io.constellationnetwork.node.shared.domain.tokenlock.ContextualTokenLockValidator.{
  ContextualTokenLockValidationErrorOr,
  CustomValidationError,
  ValidConflictResolveResult
}
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockValidator.TokenLockValidationError
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.tokenLock.TokenLockAmount._
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockOrdinal, TokenLockReference}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

trait CustomContextualTokenLockValidator {
  def validate(
    hashedTokenLock: Hashed[TokenLock],
    context: TokenLockValidatorContext
  ): Either[CustomValidationError, Hashed[TokenLock]]
}

case class TokenLockValidatorContext(
  sourceTokenLocks: Option[SortedMap[TokenLockOrdinal, StoredTokenLock]],
  sourceBalance: Balance,
  sourceLastTokenLocksRef: TokenLockReference,
  currentOrdinal: SnapshotOrdinal,
  currentEpochProgress: EpochProgress
)

trait ContextualTokenLockValidator {

  def validate(
    hashedTokenLock: Hashed[TokenLock],
    context: TokenLockValidatorContext
  ): ContextualTokenLockValidationErrorOr[ValidConflictResolveResult]
}

object ContextualTokenLockValidator {

  def make(
    customValidator: Option[CustomContextualTokenLockValidator],
    tokenLocksConfig: TokenLocksConfig
  ): ContextualTokenLockValidator =
    new ContextualTokenLockValidator {
      def validate(
        hashedTokenLocks: Hashed[TokenLock],
        context: TokenLockValidatorContext
      ): ContextualTokenLockValidationErrorOr[ValidConflictResolveResult] =
        validateLastTokenLockRef(hashedTokenLocks, context.sourceTokenLocks, context.sourceLastTokenLocksRef)
          .flatMap(validateEpochProgress(_, context.currentEpochProgress))
          .flatMap(validateConflictAtOrdinal(_, context.sourceTokenLocks))
          .flatMap {
            case (conflictResolveResult, conflictResolvedStoredTxs) =>
              validateBalances(conflictResolveResult.tx, conflictResolvedStoredTxs, context.sourceBalance)
                .map(_ => conflictResolveResult)
          }
          .flatMap { conflictResolveResult =>
            customValidator match {
              case Some(validator) =>
                validator
                  .validate(hashedTokenLocks, context)
                  .leftWiden[ContextualTokenLockValidationError]
                  .as(conflictResolveResult)
              case None => conflictResolveResult.asRight
            }
          }
          .toValidatedNec

      private def validateBalances(
        tokenLock: Hashed[TokenLock],
        txs: Option[SortedMap[TokenLockOrdinal, StoredTokenLock]],
        balance: Balance
      ): Either[ContextualTokenLockValidationError, Hashed[TokenLock]] = {
        val availableBalance = getBalanceAffectedByTxs(txs, balance)
        if (tokenLock.amount.value <= availableBalance.value)
          tokenLock.asRight
        else
          InsufficientBalance(tokenLock.amount, availableBalance).asLeft
      }

      private def validateEpochProgress(
        tokenLock: Hashed[TokenLock],
        currentEpochProgress: EpochProgress
      ): Either[ContextualTokenLockValidationError, Hashed[TokenLock]] = {
        val validUnlockEpochProgress =
          tokenLock.unlockEpoch >= (currentEpochProgress |+| EpochProgress(tokenLocksConfig.minEpochProgressesToLock))

        if (validUnlockEpochProgress) {
          tokenLock.asRight
        } else
          TooShortUnlockEpochProgress(tokenLock.unlockEpoch).asLeft
      }

      private def validateConflictAtOrdinal(
        tokenLock: Hashed[TokenLock],
        txs: Option[SortedMap[TokenLockOrdinal, StoredTokenLock]]
      ): Either[
        ContextualTokenLockValidationError,
        (ValidConflictResolveResult, Option[SortedMap[TokenLockOrdinal, StoredTokenLock]])
      ] =
        txs match {
          case Some(txs) =>
            resolveConflict(tokenLock, txs) match {
              case noConflict @ NoConflict(_) => (noConflict, txs.some).asRight
              case canOverride @ CanOverride(_) =>
                (canOverride, txs.filterNot { case (ordinal, _) => ordinal > tokenLock.ordinal }.some).asRight
              case CannotOverride(_, existing, current) => Conflict(tokenLock.ordinal, existing.hash, current.hash).asLeft
            }
          case None => (NoConflict(tokenLock), none).asRight
        }

      private def validateLastTokenLockRef(
        tokenLock: Hashed[TokenLock],
        txs: Option[SortedMap[TokenLockOrdinal, StoredTokenLock]],
        lastProcessedTokenLockRef: TokenLockReference
      ): Either[ContextualTokenLockValidationError, Hashed[TokenLock]] =
        if (tokenLock.parent.ordinal < lastProcessedTokenLockRef.ordinal)
          ParentOrdinalLowerThenLastProcessedTxOrdinal(tokenLock.parent.ordinal, lastProcessedTokenLockRef.ordinal).asLeft
        else if (tokenLock.parent === lastProcessedTokenLockRef) {
          tokenLock.asRight
        } else {
          val hasValidParent = txs.exists(_.exists {
            case (parentOrdinal, parentTx) => parentTx.ref === tokenLock.parent && parentOrdinal > lastProcessedTokenLockRef.ordinal
          })

          if (hasValidParent) tokenLock.asRight else HasNoMatchingParent(tokenLock.parent.hash).asLeft
        }

      private def getBalanceAffectedByTxs(
        txs: Option[SortedMap[TokenLockOrdinal, StoredTokenLock]],
        latestBalance: Balance
      ): Balance =
        txs match {
          case Some(txs) =>
            getTransactionsAboveMajority(txs)
              .foldLeftM(latestBalance) {
                case (acc, curr) =>
                  acc.minus(curr.transaction.amount)
              }
              .getOrElse(Balance.empty)
          case None => latestBalance
        }

      private def resolveConflict(
        tokenLock: Hashed[TokenLock],
        txs: SortedMap[TokenLockOrdinal, StoredTokenLock]
      ): ConflictResolveResult =
        txs.get(tokenLock.ordinal) match {
          case Some(WaitingTokenLock(existing)) if existing.amount < tokenLock.amount => CanOverride(tokenLock)
          case Some(tx) => CannotOverride(tokenLock, tx.ref, TokenLockReference.of(tokenLock))
          case None     => NoConflict(tokenLock)
        }

      private def getTransactionsAboveMajority(
        txs: SortedMap[TokenLockOrdinal, StoredTokenLock]
      ): SortedMap[TokenLockOrdinal, NonMajorityTokenLock] =
        txs.collect {
          case (ordinal, stored: NonMajorityTokenLock) => (ordinal, stored)
        }
    }

  @derive(eqv)
  sealed trait ConflictResolveResult
  @derive(eqv)
  sealed trait ValidConflictResolveResult extends ConflictResolveResult {
    val tx: Hashed[TokenLock]
  }
  case class NoConflict(tx: Hashed[TokenLock]) extends ValidConflictResolveResult
  case class CanOverride(tx: Hashed[TokenLock]) extends ValidConflictResolveResult
  case class CannotOverride(tx: Hashed[TokenLock], existingRef: TokenLockReference, newRef: TokenLockReference)
      extends ConflictResolveResult

  @derive(eqv, show)
  sealed trait ContextualTokenLockValidationError
  case class ParentOrdinalLowerThenLastProcessedTxOrdinal(parentOrdinal: TokenLockOrdinal, lastTxOrdinal: TokenLockOrdinal)
      extends ContextualTokenLockValidationError
  case class HasNoMatchingParent(parentHash: Hash) extends ContextualTokenLockValidationError
  case class Conflict(ordinal: TokenLockOrdinal, existingHash: Hash, newHash: Hash) extends ContextualTokenLockValidationError
  case class InsufficientBalance(amount: Amount, balance: Balance) extends ContextualTokenLockValidationError
  case class NonContextualValidationError(error: TokenLockValidationError) extends ContextualTokenLockValidationError
  case class LockedAddressError(address: Address) extends ContextualTokenLockValidationError
  case class TooShortUnlockEpochProgress(epochProgress: EpochProgress) extends ContextualTokenLockValidationError
  case class CustomValidationError(message: String) extends ContextualTokenLockValidationError

  type ContextualTokenLockValidationErrorOr[A] = ValidatedNec[ContextualTokenLockValidationError, A]
}
