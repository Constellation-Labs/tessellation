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
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

trait TransactionService[F[_]] {
  def offer(signedTransaction: Signed[Transaction]): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]]
}

object TransactionService {

  def make[F[_]: Async: KryoSerializer](
    transactionStorage: TransactionStorage[F],
    contextualTransactionValidator: ContextualTransactionValidator[F]
  ): TransactionService[F] = new TransactionService[F] {

    def offer(
      signedTransaction: Signed[Transaction]
    ): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]] =
      contextualTransactionValidator.validate(signedTransaction).flatMap {
        case Valid(_) =>
          signedTransaction
            .toHashed[F]
            .flatTap { transactionStorage.put }
            .map(_.hash.asRight[NonEmptyList[ContextualTransactionValidationError]])
        case Invalid(e) => e.toNonEmptyList.asLeft[Hash].pure[F]
      }

  }
}
