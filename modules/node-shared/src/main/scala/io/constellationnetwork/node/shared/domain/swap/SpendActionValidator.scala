package io.constellationnetwork.node.shared.domain.swap

import cats.Applicative
import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNec
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator.{SpendActionValidationError, SpendActionValidationErrorOr}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SpendAction, SpendTransaction}
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

trait SpendActionValidator[F[_]] {
  def validate(
    spendAction: SpendAction,
    activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    allBalances: Map[Option[Address], SortedMap[Address, Balance]],
    currencyId: Address
  ): F[SpendActionValidationErrorOr[SpendAction]]

  def validateReturningAcceptedAndRejected(
    spendActions: Map[Address, List[SpendAction]],
    activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    allBalances: Map[Option[Address], SortedMap[Address, Balance]]
  ): F[(Map[Address, List[SpendAction]], Map[Address, (SpendAction, List[SpendActionValidationError])])]
}

object SpendActionValidator {
  def make[F[_]: Async: Hasher]: SpendActionValidator[F] = new SpendActionValidator[F] {

    def validateReturningAcceptedAndRejected(
      spendActions: Map[Address, List[SpendAction]],
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allBalances: Map[Option[Address], SortedMap[Address, Balance]]
    ): F[
      (
        Map[Address, List[SpendAction]],
        Map[Address, (SpendAction, List[SpendActionValidationError])]
      )
    ] = {
      def processActionsForCurrency(
        currencyId: Address,
        currencySpendActions: List[SpendAction],
        currentAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
      ): F[
        (
          SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
          (Address, (List[(SpendAction, List[SpendActionValidationError])], List[SpendAction]))
        )
      ] =
        currencySpendActions
          .foldLeftM(
            (currentAllowSpends, List.empty[(SpendAction, List[SpendActionValidationError])], List.empty[SpendAction])
          ) {
            case ((allowSpendsAcc, rejectedSpendActions, acceptedSpendActions), action) =>
              validate(action, allowSpendsAcc, allBalances, currencyId).flatMap {
                case Valid(validAction) =>
                  updateCurrentAllowSpendsForValidation(validAction, allowSpendsAcc).map { updated =>
                    (updated, rejectedSpendActions, validAction :: acceptedSpendActions)
                  }
                case Invalid(errors) =>
                  Async[F].pure((allowSpendsAcc, (action -> errors.toNonEmptyList.toList) :: rejectedSpendActions, acceptedSpendActions))
              }
          }
          .map {
            case (updatedAllowSpends, rejected, accepted) =>
              updatedAllowSpends -> (currencyId -> (rejected.reverse, accepted.reverse))
          }

      spendActions.toList
        .foldLeftM(
          (activeAllowSpends, List.empty[(Address, (List[(SpendAction, List[SpendActionValidationError])], List[SpendAction]))])
        ) {
          case ((allowSpendsAcc, results), (currencyId, currencySpendActions)) =>
            processActionsForCurrency(currencyId, currencySpendActions, allowSpendsAcc).map {
              case (updatedAllowSpends, result) =>
                (updatedAllowSpends, result :: results)
            }
        }
        .map {
          case (_, spendTransactionsValidations) =>
            val acceptedSpendActions = spendTransactionsValidations.map {
              case (address, (_, accepted)) => address -> accepted
            }.filter {
              case (_, spendAction) => spendAction.nonEmpty
            }.toMap

            val rejectedSpendActions = spendTransactionsValidations.flatMap {
              case (address, (rejected, _)) =>
                rejected.map {
                  case (action: SpendAction, errors: List[SpendActionValidationError]) =>
                    address -> (action, errors)
                }
            }.toMap

            (acceptedSpendActions, rejectedSpendActions)
        }
    }

    def validate(
      spendAction: SpendAction,
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allBalances: Map[Option[Address], SortedMap[Address, Balance]],
      currencyId: Address
    ): F[SpendActionValidationErrorOr[SpendAction]] = {
      val hasDuplicatedAllowSpendReference = spendAction.spendTransactions
        .groupBy(_.allowSpendRef)
        .collect { case (Some(hash), value) => (hash, value) }
        .exists { case (_, value) => value.size > 1 }

      if (hasDuplicatedAllowSpendReference) {
        (DuplicatedAllowSpendReference(
          s"Duplicated allow spend reference in the same SpendAction"
        ): SpendActionValidationError).invalidNec[SpendAction].pure[F]
      } else {
        val validations = spendAction.spendTransactions.traverse { spendTransaction =>
          validateSpendTx(spendTransaction, activeAllowSpends, allBalances, currencyId)
        }

        validations.map(_.sequence.as(spendAction))
      }
    }

    private def updateCurrentAllowSpendsForValidation(
      validAction: SpendAction,
      currentActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
    ): F[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]] = {
      def removeAllowSpendRef(
        acc: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
        currencyId: Option[Address],
        source: Address,
        ref: Hash
      ): F[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]] = {
        val currencyActiveAllowSpends = acc.get(currencyId)
        currencyActiveAllowSpends.flatMap(_.get(source)) match {
          case Some(allowSpends) =>
            allowSpends.toList.filterA(_.toHashed.map(_.hash =!= ref)).map { filtered =>
              val updatedSet = SortedSet.from(filtered)

              val updatedCurrencyMap =
                if (updatedSet.nonEmpty)
                  acc(currencyId) + (source -> updatedSet)
                else
                  acc(currencyId) - source

              val updatedAllowSpends =
                if (updatedCurrencyMap.nonEmpty)
                  acc + (currencyId -> updatedCurrencyMap)
                else
                  acc - currencyId

              updatedAllowSpends
            }

          case None => acc.pure[F]
        }
      }

      val txnsToRemove = validAction.spendTransactions.collect {
        case txn if txn.allowSpendRef.isDefined =>
          (txn.currencyId.map(_.value), txn.source, txn.allowSpendRef.get)
      }

      txnsToRemove.foldM(currentActiveAllowSpends) {
        case (acc, (currencyId, source, ref)) =>
          removeAllowSpendRef(acc, currencyId, source, ref)
      }
    }

    private def validateSpendTx(
      tx: SpendTransaction,
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allBalances: Map[Option[Address], SortedMap[Address, Balance]],
      currencyId: Address
    ): F[SpendActionValidationErrorOr[SpendTransaction]] =
      activeAllowSpends
        .get(tx.currencyId.map(_.value))
        .map(validateAllowSpendRef(tx, _, allBalances, currencyId))
        .getOrElse(
          Applicative[F]
            .pure(NoActiveAllowSpends(s"Currency ${tx.currencyId} not found in active allow spends").invalidNec[SpendTransaction])
        )

    private def validateAllowSpendRef(
      spendTransaction: SpendTransaction,
      activeAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      allBalances: Map[Option[Address], SortedMap[Address, Balance]],
      currencyId: Address
    ): F[SpendActionValidationErrorOr[SpendTransaction]] =
      spendTransaction.allowSpendRef match {
        case Some(allowSpendRef) =>
          activeAllowSpends.toList.traverse {
            case (_, hashedAllowSpends) =>
              hashedAllowSpends.toList.traverse(_.toHashed).map { hashedList =>
                hashedList.map(hashed => hashed.hash -> hashed.signed)
              }
          }
            .map(_.flatten.toMap)
            .map { allowSpendHashes =>
              allowSpendHashes.get(allowSpendRef) match {
                case None =>
                  AllowSpendNotFound(
                    s"Allow spend $allowSpendRef not found in currency active allow spends"
                  ).invalidNec[SpendTransaction]

                case Some(signedAllowSpend) =>
                  if (signedAllowSpend.currencyId =!= spendTransaction.currencyId)
                    InvalidCurrency(
                      s"Currency mismatch: expected ${signedAllowSpend.currencyId}, found ${spendTransaction.currencyId}"
                    ).invalidNec[SpendTransaction]
                  else if (signedAllowSpend.destination =!= currencyId)
                    InvalidCurrencyId(
                      s"Currency mismatch: expected $currencyId, found ${signedAllowSpend.currencyId}"
                    ).invalidNec[SpendTransaction]
                  else if (!signedAllowSpend.approvers.contains(currencyId))
                    InvalidCurrencyId(
                      s"Currency mismatch: expected $currencyId, found ${signedAllowSpend.currencyId}"
                    ).invalidNec[SpendTransaction]
                  else if (signedAllowSpend.destination =!= spendTransaction.destination)
                    InvalidDestinationAddress(
                      s"Invalid destination address. Found: ${spendTransaction.destination}. Expected: ${signedAllowSpend.destination}"
                    ).invalidNec[SpendTransaction]
                  else if (signedAllowSpend.source =!= spendTransaction.source)
                    InvalidSourceAddress(
                      s"Invalid source address. Found: ${spendTransaction.source}. Expected: ${signedAllowSpend.source}"
                    ).invalidNec[SpendTransaction]
                  else if (signedAllowSpend.amount.value.value < spendTransaction.amount.value.value)
                    SpendAmountGreaterThanAllowed(
                      s"Spend amount: ${spendTransaction.amount} greater than allowed: ${signedAllowSpend.amount}"
                    ).invalidNec[SpendTransaction]
                  else
                    spendTransaction.validNec[SpendActionValidationError]
              }
            }

        case None =>
          val spendTransactionCurrencyAddress = spendTransaction.currencyId.map(_.value)
          val spendTransactionCurrencyBalances = allBalances.getOrElse(spendTransactionCurrencyAddress, SortedMap.empty[Address, Balance])
          val currencyIdBalance = spendTransactionCurrencyBalances.getOrElse(currencyId, Balance.empty)

          if (spendTransaction.amount.value.value > currencyIdBalance.value.value)
            (NotEnoughCurrencyIdBalance(
              s"Spend amount: ${spendTransaction.amount} greater than currencyId balance: $currencyIdBalance"
            ): SpendActionValidationError).invalidNec[SpendTransaction].pure[F]
          else if (spendTransaction.source =!= currencyId)
            (InvalidSourceAddress(
              s"Invalid source address. Found: ${spendTransaction.source}. Expected: $currencyId"
            ): SpendActionValidationError).invalidNec[SpendTransaction].pure[F]
          else
            spendTransaction.validNec[SpendActionValidationError].pure[F]
      }
  }

  @derive(eqv, show)
  sealed trait SpendActionValidationError
  case class NoActiveAllowSpends(error: String) extends SpendActionValidationError
  case class InvalidDestinationAddress(error: String) extends SpendActionValidationError
  case class InvalidSourceAddress(error: String) extends SpendActionValidationError
  case class AllowSpendNotFound(error: String) extends SpendActionValidationError
  case class InvalidCurrency(error: String) extends SpendActionValidationError
  case class SpendAmountGreaterThanAllowed(error: String) extends SpendActionValidationError
  case class NotEnoughCurrencyIdBalance(error: String) extends SpendActionValidationError
  case class InvalidCurrencyId(error: String) extends SpendActionValidationError
  case class DuplicatedAllowSpendReference(error: String) extends SpendActionValidationError

  type SpendActionValidationErrorOr[A] = ValidatedNec[SpendActionValidationError, A]
}
