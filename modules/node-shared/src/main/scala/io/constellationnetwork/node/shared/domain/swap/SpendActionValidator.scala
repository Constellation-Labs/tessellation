package io.constellationnetwork.node.shared.domain.swap

import cats.Applicative
import cats.data.ValidatedNec
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SpendAction, SpendTransaction}
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

import SpendActionValidator.SpendActionValidationErrorOr

trait SpendActionValidator[F[_]] {
  def validate(
    spendAction: SpendAction,
    activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
  ): F[SpendActionValidationErrorOr[SpendAction]]
}

object SpendActionValidator {
  def make[F[_]: Async: Hasher]: SpendActionValidator[F] = new SpendActionValidator[F] {
    def validate(
      spendAction: SpendAction,
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
    ): F[SpendActionValidationErrorOr[SpendAction]] =
      for {
        input <- validateSpendTx(spendAction.input, activeAllowSpends)
        output <- validateSpendTx(spendAction.output, activeAllowSpends)
      } yield (input *> output).as(spendAction)

    def validateSpendTx(
      tx: SpendTransaction,
      activeAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
    ): F[SpendActionValidationErrorOr[SpendTransaction]] =
      activeAllowSpends
        .get(tx.currency.map(_.value))
        .map(validateAllowSpendRef(tx, _))
        .getOrElse(
          Applicative[F].pure(NoActiveAllowSpends(s"Currency ${tx.currency} not found in active allow spends").invalidNec[SpendTransaction])
        )

    def validateAllowSpendRef(
      spendTransaction: SpendTransaction,
      activeAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]]
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
                    s"Allow spend ${allowSpendRef} not found in active allow spends"
                  ).invalidNec[SpendTransaction]

                case Some(signedAllowSpend) =>
                  if (signedAllowSpend.currency != spendTransaction.currency)
                    InvalidCurrency(
                      s"Currency mismatch: expected ${signedAllowSpend.currency}, found ${spendTransaction.currency}"
                    ).invalidNec[SpendTransaction]
                  else if (signedAllowSpend.destination != spendTransaction.destination)
                    InvalidDestinationAddress(
                      s"Destination address ${spendTransaction.destination} not found in active allow spends"
                    ).invalidNec[SpendTransaction]
                  else
                    spendTransaction.validNec[SpendActionValidationError]
              }
            }

        case None => spendTransaction.validNec[SpendActionValidationError].pure[F]
      }
  }

  @derive(eqv, show)
  sealed trait SpendActionValidationError
  case class NoActiveAllowSpends(error: String) extends SpendActionValidationError
  case class InvalidDestinationAddress(error: String) extends SpendActionValidationError
  case class AllowSpendNotFound(error: String) extends SpendActionValidationError
  case class InvalidCurrency(error: String) extends SpendActionValidationError

  type SpendActionValidationErrorOr[A] = ValidatedNec[SpendActionValidationError, A]
}
