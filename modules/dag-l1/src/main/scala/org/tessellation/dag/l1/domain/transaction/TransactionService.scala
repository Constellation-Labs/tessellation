package org.tessellation.dag.l1.domain.transaction

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.dag.l1.domain.transaction.ContextualTransactionValidator.NonContextualValidationError
import org.tessellation.ext.cats.syntax.validated.validatedSyntax
import org.tessellation.node.shared.domain.collateral.LatestBalances
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.node.shared.domain.transaction.TransactionValidator
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.hash.Hash
import org.tessellation.security.{Hashed, Hasher}

import fs2.Stream

import ContextualTransactionValidator.ContextualTransactionValidationError

trait TransactionService[F[_]] {
  def offer(transaction: Hashed[Transaction])(
    implicit hasher: Hasher[F]
  ): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]]
}

object TransactionService {

  def make[
    F[_]: Async,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    transactionStorage: TransactionStorage[F],
    lastSnapshotStorage: LastSnapshotStorage[F, S, SI] with LatestBalances[F],
    transactionValidator: TransactionValidator[F]
  ): TransactionService[F] = new TransactionService[F] {

    def offer(
      transaction: Hashed[Transaction]
    )(implicit hasher: Hasher[F]): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]] =
      transactionValidator
        .validate(transaction.signed)
        .map(_.errorMap(NonContextualValidationError))
        .flatMap {
          case Valid(_) =>
            lastSnapshotStorage.getCombinedStream.map {
              case Some((s, si)) => (s.ordinal, si.balances.getOrElse(transaction.source, Balance.empty))
              case None          => (SnapshotOrdinal.MinValue, Balance.empty)
            }.changes.switchMap {
              case (latestOrdinal, balance) => Stream.eval(transactionStorage.tryPut(transaction, latestOrdinal, balance))
            }.head.compile.last.flatMap {
              case Some(value) => value.pure[F]
              case None =>
                new Exception(s"Unexpected state, stream should always emit the first snapshot")
                  .raiseError[F, Either[NonEmptyList[ContextualTransactionValidationError], Hash]]
            }
          case Invalid(e) =>
            e.toNonEmptyList.asLeft[Hash].leftWiden[NonEmptyList[ContextualTransactionValidationError]].pure[F]
        }
  }
}
