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
  *   - references an [[AllowSpend]] (`burnFrom`): the referenced allow spend must match currency, approver, and source, and reserve at
  *     least the burned amount; or
  *   - has no reference (self-burn): the source must be the metagraph's own address (`currencyId`) and have enough balance.
  *
  * Net effect in all cases: the amount is destroyed, reducing totalSupply. There is never a destination credit.
  */
trait BurnActionValidator[F[_]] {
  def validate(
    burnAction: BurnAction,
    activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    allBalances: Map[Option[Address], SortedMap[Address, Balance]],
    currencyId: Address,
    spendConsumedRefs: Set[Hash]
  ): F[BurnActionValidationErrorOr[BurnAction]]

  def validateReturningAcceptedAndRejected(
    burnActions: Map[Address, List[BurnAction]],
    activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    allBalances: Map[Option[Address], SortedMap[Address, Balance]],
    // allow-spend refs already consumed by accepted spends in this snapshot; burnFrom on any of these is rejected (spends win).
    spendConsumedRefs: Set[Hash]
  ): F[(Map[Address, List[BurnAction]], Map[Address, (BurnAction, List[BurnActionValidationError])])]
}

object BurnActionValidator {
  def make[F[_]: Async: Hasher]: BurnActionValidator[F] = new BurnActionValidator[F] {

    def validateReturningAcceptedAndRejected(
      burnActions: Map[Address, List[BurnAction]],
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allBalances: Map[Option[Address], SortedMap[Address, Balance]],
      spendConsumedRefs: Set[Hash]
    ): F[
      (
        Map[Address, List[BurnAction]],
        Map[Address, (BurnAction, List[BurnActionValidationError])]
      )
    ] = {
      def processActionsForCurrency(
        currencyId: Address,
        currencyBurnActions: List[BurnAction],
        currentAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
        currentBalances: Map[Option[Address], SortedMap[Address, Balance]]
      ): F[
        (
          SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
          Map[Option[Address], SortedMap[Address, Balance]],
          (Address, (List[(BurnAction, List[BurnActionValidationError])], List[BurnAction]))
        )
      ] =
        currencyBurnActions
          .foldLeftM(
            (
              currentAllowSpends,
              currentBalances,
              List.empty[(BurnAction, List[BurnActionValidationError])],
              List.empty[BurnAction]
            )
          ) {
            case ((allowSpendsAcc, balancesAcc, rejectedBurnActions, acceptedBurnActions), action) =>
              validate(action, allowSpendsAcc, balancesAcc, currencyId, spendConsumedRefs).flatMap {
                case Valid(validAction) =>
                  updateCurrentAllowSpendsForValidation(validAction, allowSpendsAcc).map { updatedAllowSpends =>
                    updateCurrentBalancesForValidation(validAction, balancesAcc, currencyId) match {
                      case Right(updatedBalances) =>
                        (updatedAllowSpends, updatedBalances, rejectedBurnActions, validAction :: acceptedBurnActions)
                      case Left(error) =>
                        (allowSpendsAcc, balancesAcc, (action -> List(error)) :: rejectedBurnActions, acceptedBurnActions)
                    }
                  }
                case Invalid(errors) =>
                  Async[F].pure(
                    (allowSpendsAcc, balancesAcc, (action -> errors.toNonEmptyList.toList) :: rejectedBurnActions, acceptedBurnActions)
                  )
              }
          }
          .map {
            case (updatedAllowSpends, updatedBalances, rejected, accepted) =>
              (updatedAllowSpends, updatedBalances, currencyId -> (rejected.reverse, accepted.reverse))
          }

      burnActions.toList
        .foldLeftM(
          (
            activeAllowSpends,
            allBalances,
            List.empty[(Address, (List[(BurnAction, List[BurnActionValidationError])], List[BurnAction]))]
          )
        ) {
          case ((allowSpendsAcc, balancesAcc, results), (currencyId, currencyBurnActions)) =>
            processActionsForCurrency(currencyId, currencyBurnActions, allowSpendsAcc, balancesAcc).map {
              case (updatedAllowSpends, updatedBalances, result) =>
                (updatedAllowSpends, updatedBalances, result :: results)
            }
        }
        .map {
          case (_, _, burnTransactionsValidations) =>
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
      currencyId: Address,
      spendConsumedRefs: Set[Hash]
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
          if (burnTransaction.allowSpendRef.exists(spendConsumedRefs.contains))
            (AllowSpendConsumedBySpend(
              s"Allow spend ${burnTransaction.allowSpendRef} already consumed by an accepted spend in this snapshot"
            ): BurnActionValidationError).invalidNec[BurnTransaction].pure[F]
          else
            validateAllowSpendRef(burnTransaction, activeAllowSpends, allBalances, currencyId)
        }

        validations.map { transactionValidations =>
          (
            transactionValidations.sequence,
            validateCumulativeSelfBurns(burnAction, allBalances, currencyId)
          ).mapN((_, _) => burnAction)
        }
      }
    }

    private def validateCumulativeSelfBurns(
      burnAction: BurnAction,
      allBalances: Map[Option[Address], SortedMap[Address, Balance]],
      currencyId: Address
    ): BurnActionValidationErrorOr[Unit] = {
      val selfBurnAmountsByCurrency = burnAction.burnTransactions.toList.collect {
        case txn if txn.allowSpendRef.isEmpty && txn.source === currencyId =>
          txn.currencyId.map(_.value) -> BigInt(txn.amount.value.value)
      }.groupMap { case (currencyAddress, _) => currencyAddress } { case (_, amount) => amount }

      selfBurnAmountsByCurrency.toList.traverse {
        case (currencyAddress, amounts) =>
          val total = amounts.sum
          val currentBalances = allBalances.getOrElse(currencyAddress, SortedMap.empty[Address, Balance])
          val currencyIdBalance = currentBalances.getOrElse(currencyId, Balance.empty)

          if (amounts.size > 1 && total > BigInt(currencyIdBalance.value.value))
            (NotEnoughCurrencyIdBalance(
              s"Total burn amount: $total greater than currencyId balance: $currencyIdBalance"
            ): BurnActionValidationError).invalidNec[Unit]
          else
            ().validNec[BurnActionValidationError]
      }.void
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

    private def updateCurrentBalancesForValidation(
      validAction: BurnAction,
      currentBalances: Map[Option[Address], SortedMap[Address, Balance]],
      currencyId: Address
    ): Either[BurnActionValidationError, Map[Option[Address], SortedMap[Address, Balance]]] =
      validAction.burnTransactions.toList
        .filter(txn => txn.allowSpendRef.isEmpty && txn.source === currencyId)
        .foldLeft[Either[BurnActionValidationError, Map[Option[Address], SortedMap[Address, Balance]]]](Right(currentBalances)) {
          case (accEither, burnTransaction) =>
            accEither.flatMap { acc =>
              val currencyAddress = burnTransaction.currencyId.map(_.value)
              val currencyBalances = acc.getOrElse(currencyAddress, SortedMap.empty[Address, Balance])
              val currentBalance = currencyBalances.getOrElse(currencyId, Balance.empty)

              currentBalance
                .minus(io.constellationnetwork.schema.swap.SwapAmount.toAmount(burnTransaction.amount))
                .map { updatedBalance =>
                  acc.updated(currencyAddress, currencyBalances.updated(currencyId, updatedBalance))
                }
                .leftMap { error =>
                  NotEnoughCurrencyIdBalance(s"Balance arithmetic error updating validation balances by burn transactions: $error")
                }
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
  case class AllowSpendConsumedBySpend(error: String) extends BurnActionValidationError

  type BurnActionValidationErrorOr[A] = ValidatedNec[BurnActionValidationError, A]
}
