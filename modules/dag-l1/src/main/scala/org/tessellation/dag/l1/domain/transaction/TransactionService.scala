package org.tessellation.dag.l1.domain.transaction

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.transaction.TransactionValidator
import org.tessellation.dag.transaction.TransactionValidator.TransactionValidationError
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

trait TransactionService[F[_]] {
  def offer(signedTransaction: Signed[Transaction]): F[Either[NonEmptyList[TransactionValidationError], Hash]]
}

object TransactionService {

  def make[F[_]: Sync: KryoSerializer](
    transactionStorage: TransactionStorage[F],
    transactionValidator: TransactionValidator[F]
  ): TransactionService[F] = new TransactionService[F] {

    def offer(
      signedTransaction: Signed[Transaction]
    ): F[Either[NonEmptyList[TransactionValidationError], Hash]] =
      transactionValidator.validate(signedTransaction).flatMap {
        case Valid(_) =>
          for {
            hash <- signedTransaction.value.hashF
            _ <- transactionStorage.put(signedTransaction)
          } yield hash.asRight[NonEmptyList[TransactionValidationError]]
        case Invalid(e) => e.toNonEmptyList.asLeft[Hash].pure[F]
      }

  }
}
