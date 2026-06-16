package io.constellationnetwork.node.shared.domain.swap

import cats.Applicative
import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNec
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.domain.swap.BurnActionValidator.{BurnActionValidationError, BurnActionValidationErrorOr}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{BurnAction, BurnTransaction}
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

/** Validator for [[BurnAction]]s.
  *
  * Mirrors [[SpendActionValidator]] exactly, minus the destination check (a [[BurnTransaction]] has no destination). A burn either:
  *   - references an [[AllowSpend]] (`burnFrom`): the referenced allow spend must match currency, approver, and source, and reserve at least
  *     the burned amount; or
  *   - has no reference (self-burn): the source must be the metagraph's own address (`currencyId`) and have enough balance.
  *
  * Net effect in all cases: the amount is destroyed, reducing totalSupply. There is never a destination credit.
  */
trait BurnActionValidator[F[_]] {
  def validate(
    burnAction: BurnAction,
    activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    allBalances: Map[Option[Address], SortedMap[Address, Balance]],
    currencyId: Address
  ): F[BurnActionValidationErrorOr[BurnAction]]

  def validateReturningAcceptedAndRejected(
    burnActions: Map[Address, List[BurnAction]],
    activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    allBalances: Map[Option[Address], SortedMap[Address, Balance]]
  ): F[(Map[Address, List[BurnAction]], Map[Address, (BurnAction, List[BurnActionValidationError])])]
}

object BurnActionValidator {
  def make[F[_]: Async: Hasher]: BurnActionValidator[F] = new BurnActionValidator[F] {

    def validateReturningAcceptedAndRejected(
      burnActions: Map[Address, List[BurnAction]],
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allBalances: Map[Option[Address], SortedMap[Address, Balance]]
    ): F[
      (
        Map[Address, List[BurnAction]],
        Map[Address, (BurnAction, List[BurnActionValidationError])]
      )
    ] = {
      def processActionsForCurrency(
        currencyId: Address,
        currencyBurnActions: List[BurnAction],
        currentAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
      ): F[
        (
          SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
          (Address, (List[(BurnAction, List[BurnActionValidationError])], List[BurnAction]))
        )
      ] =
        currencyBurnActions
          .foldLeftM(
            (currentAllowSpends, List.empty[(BurnAction, List[BurnActionValidationError])], List.empty[BurnAction])
          ) {
            case ((allowSpendsAcc, rejectedBurnActions, acceptedBurnActions), action) =>
              validate(action, allowSpendsAcc, allBalances, currencyId).flatMap {
                case Valid(validAction) =>
                  updateCurrentAllowSpendsForValidation(validAction, allowSpendsAcc).map { updated =>
                    (updated, rejectedBurnActions, validAction :: acceptedBurnActions)
                  }
                case Invalid(errors) =>
                  Async[F].pure((allowSpendsAcc, (action -> errors.toNonEmptyList.toList) :: rejectedBurnActions, acceptedBurnActions))
              }
          }
          .map {
            case (updatedAllowSpends, rejected, accepted) =>
              updatedAllowSpends -> (currencyId -> (rejected.reverse, accepted.reverse))
          }

      burnActions.toList
        .foldLeftM(
          (activeAllowSpends, List.empty[(Address, (List[(BurnAction, List[BurnActionValidationError])], List[BurnAction]))])
        ) {
          case ((allowSpendsAcc, results), (currencyId, currencyBurnActions)) =>
            processActionsForCurrency(currencyId, currencyBurnActions, allowSpendsAcc).map {
              case (updatedAllowSpends, result) =>
                (updatedAllowSpends, result :: results)
            }
        }
        .map {
          case (_, burnTransactionsValidations) =>
            val acceptedBurnActions = burnTransactionsValidations.map {
              case (address, (_, accepted)) => address -> accepted
            }.filter {
              case (_, burnAction) => burnAction.nonEmpty
            }.toMap

            val rejectedBurnActions = burnTransactionsValidations.flatMap {
              case (address, (rejected, _)) =>
                rejected.map {
                  case (action: BurnAction, errors: List[BurnActionValidationError]) =>
                    address -> (action, errors)
                }
            }.toMap

            (acceptedBurnActions, rejectedBurnActions)
        }
    }

    def validate(
      burnAction: BurnAction,
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allBalances: Map[Option[Address], SortedMap[Address, Balance]],
      currencyId: Address
    ): F[BurnActionValidationErrorOr[BurnAction]] = {
      val hasDuplicatedAllowSpendReference = burnAction.burnTransactions
        .groupBy(_.allowSpendRef)
        .collect { case (Some(hash), value) => (hash, value) }
        .exists { case (_, value) => value.size > 1 }

      if (hasDuplicatedAllowSpendReference) {
        (DuplicatedAllowSpendReference(
          s"Duplicated allow spend reference in the same BurnAction"
        ): BurnActionValidationError).invalidNec[BurnAction].pure[F]
      } else {
        val validations = burnAction.burnTransactions.traverse { burnTransaction =>
          validateAllowSpendRef(burnTransaction, activeAllowSpends, allBalances, currencyId)
        }

        validations.map(_.sequence.as(burnAction))
      }
    }

    private def updateCurrentAllowSpendsForValidation(
      validAction: BurnAction,
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

      val txnsToRemove = validAction.burnTransactions.collect {
        case txn if txn.allowSpendRef.isDefined =>
          (txn.currencyId.map(_.value), txn.source, txn.allowSpendRef.get)
      }

      txnsToRemove.foldM(currentActiveAllowSpends) {
        case (acc, (currencyId, source, ref)) =>
          removeAllowSpendRef(acc, currencyId, source, ref)
      }
    }

    private def validateAllowSpendRef(
      burnTransaction: BurnTransaction,
      currentActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allBalances: Map[Option[Address], SortedMap[Address, Balance]],
      currencyId: Address
    ): F[BurnActionValidationErrorOr[BurnTransaction]] =
      burnTransaction.allowSpendRef match {
        case Some(allowSpendRef) =>
          currentActiveAllowSpends
            .get(burnTransaction.currencyId.map(_.value))
            .map { activeAllowSpends =>
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
                      ).invalidNec[BurnTransaction]

                    case Some(signedAllowSpend) =>
                      if (signedAllowSpend.currencyId =!= burnTransaction.currencyId)
                        InvalidCurrency(
                          s"Currency mismatch: expected ${signedAllowSpend.currencyId}, found ${burnTransaction.currencyId}"
                        ).invalidNec[BurnTransaction]
                      else if (signedAllowSpend.destination =!= currencyId)
                        InvalidCurrencyId(
                          s"Currency mismatch: expected $currencyId, found ${signedAllowSpend.currencyId}"
                        ).invalidNec[BurnTransaction]
                      else if (!signedAllowSpend.approvers.contains(currencyId))
                        InvalidCurrencyId(
                          s"Currency mismatch: expected $currencyId, found ${signedAllowSpend.currencyId}"
                        ).invalidNec[BurnTransaction]
                      else if (signedAllowSpend.source =!= burnTransaction.source)
                        InvalidSourceAddress(
                          s"Invalid source address. Found: ${burnTransaction.source}. Expected: ${signedAllowSpend.source}"
                        ).invalidNec[BurnTransaction]
                      else if (signedAllowSpend.amount.value.value < burnTransaction.amount.value.value)
                        BurnAmountGreaterThanAllowed(
                          s"Burn amount: ${burnTransaction.amount} greater than allowed: ${signedAllowSpend.amount}"
                        ).invalidNec[BurnTransaction]
                      else
                        burnTransaction.validNec[BurnActionValidationError]
                  }
                }
            }
            .getOrElse(
              Applicative[F]
                .pure(
                  NoActiveAllowSpends(s"Currency ${burnTransaction.currencyId} not found in active allow spends")
                    .invalidNec[BurnTransaction]
                )
            )
        case None =>
          val burnTransactionCurrencyAddress = burnTransaction.currencyId.map(_.value)
          val burnTransactionCurrencyBalances = allBalances.getOrElse(burnTransactionCurrencyAddress, SortedMap.empty[Address, Balance])
          val currencyIdBalance = burnTransactionCurrencyBalances.getOrElse(currencyId, Balance.empty)

          if (burnTransaction.amount.value.value > currencyIdBalance.value.value)
            (NotEnoughCurrencyIdBalance(
              s"Burn amount: ${burnTransaction.amount} greater than currencyId balance: $currencyIdBalance"
            ): BurnActionValidationError).invalidNec[BurnTransaction].pure[F]
          else if (burnTransaction.source =!= currencyId)
            (InvalidSourceAddress(
              s"Invalid source address. Found: ${burnTransaction.source}. Expected: $currencyId"
            ): BurnActionValidationError).invalidNec[BurnTransaction].pure[F]
          else
            burnTransaction.validNec[BurnActionValidationError].pure[F]
      }
  }

  @derive(eqv, show)
  sealed trait BurnActionValidationError
  case class NoActiveAllowSpends(error: String) extends BurnActionValidationError
  case class InvalidSourceAddress(error: String) extends BurnActionValidationError
  case class AllowSpendNotFound(error: String) extends BurnActionValidationError
  case class InvalidCurrency(error: String) extends BurnActionValidationError
  case class BurnAmountGreaterThanAllowed(error: String) extends BurnActionValidationError
  case class NotEnoughCurrencyIdBalance(error: String) extends BurnActionValidationError
  case class InvalidCurrencyId(error: String) extends BurnActionValidationError
  case class DuplicatedAllowSpendReference(error: String) extends BurnActionValidationError

  type BurnActionValidationErrorOr[A] = ValidatedNec[BurnActionValidationError, A]
}
