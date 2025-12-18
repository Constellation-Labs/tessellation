package io.constellationnetwork.dag.l1.domain.transaction

import cats.Parallel
import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l1.domain.transaction.ContextualTransactionValidator.NonContextualValidationError
import io.constellationnetwork.ext.cats.syntax.validated.validatedSyntax
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.transaction.TransactionValidator
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.transaction.Transaction
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{Hashed, Hasher}

import io.circe.Json

import ContextualTransactionValidator.ContextualTransactionValidationError

trait TransactionService[F[_]] {
  def offer(transaction: Hashed[Transaction])(
    implicit hasher: Hasher[F]
  ): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]]
}

object TransactionService {

  def make[
    F[_]: Async: Parallel,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    transactionStorage: TransactionStorage[F],
    lastSnapshotStorage: LastSnapshotStorage[F, S, SI],
    transactionValidator: TransactionValidator[F],
    mptStore: MptStore[F, GlobalStateKey]
  ): TransactionService[F] = new TransactionService[F] {

    def offer(
      transaction: Hashed[Transaction]
    )(implicit hasher: Hasher[F]): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]] =
      transactionValidator
        .validate(transaction.signed)
        .map(_.errorMap(NonContextualValidationError))
        .flatMap {
          case Valid(_) =>
            for {
              maybeSnapshot <- lastSnapshotStorage.get
              ordinal = maybeSnapshot.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue)
              balance <- mptStore.getBalance(transaction.source).map(_.getOrElse(Balance.empty))
              result <- transactionStorage.tryPut(transaction, ordinal, balance)
            } yield result

          case Invalid(e) =>
            e.toNonEmptyList.asLeft[Hash].leftWiden[NonEmptyList[ContextualTransactionValidationError]].pure[F]
        }
  }
}
