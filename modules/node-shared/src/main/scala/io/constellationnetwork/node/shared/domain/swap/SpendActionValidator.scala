package io.constellationnetwork.node.shared.domain.swap

import cats.Applicative
import cats.data.ValidatedNec
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.Console.{MAGENTA, RESET}
import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator.SpendActionValidationErrorOr
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SpendAction, SpendTransaction}
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait SpendActionValidator[F[_]] {
  def validate(
    spendAction: SpendAction,
    activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    globalBalances: Map[Address, Balance],
    currencyId: Address,
    ordinal: SnapshotOrdinal
  ): F[SpendActionValidationErrorOr[SpendAction]]
}

object SpendActionValidator {
  def make[F[_]: Async: Hasher]: SpendActionValidator[F] = new SpendActionValidator[F] {
    private val logger = Slf4jLogger.getLogger

    def validate(
      spendAction: SpendAction,
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      globalBalances: Map[Address, Balance],
      currencyId: Address,
      ordinal: SnapshotOrdinal
    ): F[SpendActionValidationErrorOr[SpendAction]] = {
      val currencyIdBalance = globalBalances.getOrElse(currencyId, Balance.empty)
      for {
        input <- validateSpendTx(spendAction.input, activeAllowSpends, currencyIdBalance, ordinal)
        output <- validateSpendTx(spendAction.output, activeAllowSpends, currencyIdBalance, ordinal)
      } yield (input *> output).as(spendAction)
    }

    def validateSpendTx(
      tx: SpendTransaction,
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      currencyIdBalance: Balance,
      ordinal: SnapshotOrdinal
    ): F[SpendActionValidationErrorOr[SpendTransaction]] =
      activeAllowSpends
        .get(tx.currency.map(_.value))
        .map(validateAllowSpendRef(tx, _, currencyIdBalance, ordinal))
        .getOrElse(
          Applicative[F].pure(NoActiveAllowSpends(s"Currency ${tx.currency} not found in active allow spends").invalidNec[SpendTransaction])
        )

    def validateAllowSpendRef(
      spendTransaction: SpendTransaction,
      activeAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      currencyIdBalance: Balance,
      ordinal: SnapshotOrdinal
    ): F[SpendActionValidationErrorOr[SpendTransaction]] =
      spendTransaction.allowSpendRef match {
        case Some(allowSpendRef) =>
          logger.debug(
            s"${MAGENTA}Spend transaction ${spendTransaction.show} expects following allowSpendRef ${allowSpendRef.show} to be active${RESET}"
          ) >> activeAllowSpends.toList.traverse {
            case (_, hashedAllowSpends) =>
              hashedAllowSpends.toList.traverse(_.toHashed).map { hashedList =>
                hashedList.map(hashed => hashed.hash -> hashed.signed)
              }
          }
            .map(_.flatten.toMap)
            .flatTap(hashes =>
              logger.debug(
                s"${MAGENTA}We have following hashes that are active in snapshot ${ordinal.show}: ${hashes.keys.map(_.show).mkString(", ")}${RESET}"
              )
            )
            .map { allowSpendHashes =>
              allowSpendHashes.get(allowSpendRef) match {
                case None =>
                  AllowSpendNotFound(
                    s"Allow spend $allowSpendRef not found in currency active allow spends"
                  ).invalidNec[SpendTransaction]

                case Some(signedAllowSpend) =>
                  if (signedAllowSpend.currency =!= spendTransaction.currency)
                    InvalidCurrency(
                      s"Currency mismatch: expected ${signedAllowSpend.currency}, found ${spendTransaction.currency}"
                    ).invalidNec[SpendTransaction]
                  else if (signedAllowSpend.destination =!= spendTransaction.destination)
                    InvalidDestinationAddress(
                      s"Destination address ${spendTransaction.destination} not found in active allow spends"
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
          if (spendTransaction.amount.value.value > currencyIdBalance.value.value)
            (NotEnoughCurrencyIdBalance(
              s"Spend amount: ${spendTransaction.amount} greater than currencyId balance: $currencyIdBalance"
            ): SpendActionValidationError).invalidNec[SpendTransaction].pure[F]
          else
            spendTransaction.validNec[SpendActionValidationError].pure[F]
      }
  }

  @derive(eqv, show)
  sealed trait SpendActionValidationError
  case class NoActiveAllowSpends(error: String) extends SpendActionValidationError
  case class InvalidDestinationAddress(error: String) extends SpendActionValidationError
  case class AllowSpendNotFound(error: String) extends SpendActionValidationError
  case class InvalidCurrency(error: String) extends SpendActionValidationError
  case class SpendAmountGreaterThanAllowed(error: String) extends SpendActionValidationError
  case class NotEnoughCurrencyIdBalance(error: String) extends SpendActionValidationError

  type SpendActionValidationErrorOr[A] = ValidatedNec[SpendActionValidationError, A]
}
