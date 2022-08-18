package org.tessellation.dag.l1.domain.transaction

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.transaction.ContextualTransactionValidator
import org.tessellation.dag.transaction.ContextualTransactionValidator.ContextualTransactionValidationError
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash

trait TransactionService[F[_]] {
  def offer(transaction: Hashed[Transaction]): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]]
}

object TransactionService {

  def make[F[_]: Async](
    transactionStorage: TransactionStorage[F],
    contextualTransactionValidator: ContextualTransactionValidator[F]
  ): TransactionService[F] = new TransactionService[F] {

    def offer(
      transaction: Hashed[Transaction]
    ): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]] =
      contextualTransactionValidator.validate(transaction.signed).flatMap {
        case Valid(_) =>
          transactionStorage
            .put(transaction)
            .as(transaction.hash.asRight[NonEmptyList[ContextualTransactionValidationError]])
        case Invalid(e) => e.toNonEmptyList.asLeft[Hash].pure[F]
      }

  }
}
