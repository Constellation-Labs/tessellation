package io.constellationnetwork.dag.l1.domain.transaction

import cats.Parallel
import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l1.domain.transaction.ContextualTransactionValidator.NonContextualValidationError
import io.constellationnetwork.ext.cats.syntax.validated.validatedSyntax
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
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

import fs2.Stream
import io.circe.Json

import ContextualTransactionValidator.ContextualTransactionValidationError

trait TransactionService[F[_]] {
  def offer(transaction: Hashed[Transaction])(
    implicit hasher: Hasher[F]
  ): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]]
}

object TransactionService {

  def make[
    F[_]: Async: Parallel: JsonSerializer,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    transactionStorage: TransactionStorage[F],
    lastSnapshotStorage: LastSnapshotStorage[F, S, SI] with LatestBalances[F],
    transactionValidator: TransactionValidator[F],
    mptStore: MptStore[F, GlobalStateKey],
    shouldUseMptStore: Boolean
  ): TransactionService[F] = new TransactionService[F] {

    def useMptStore(
      transaction: Hashed[Transaction]
    )(implicit hasher: Hasher[F]) =
      for {
        maybeSnapshot <- lastSnapshotStorage.get
        ordinal = maybeSnapshot.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue)
        balance <- mptStore.getBalance(transaction.source).map(_.getOrElse(Balance.empty))
        result <- transactionStorage.tryPut(transaction, ordinal, balance)
      } yield result

    def useGlobalSnapshotInfo(
      transaction: Hashed[Transaction]
    ) =
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

    def offer(
      transaction: Hashed[Transaction]
    )(implicit hasher: Hasher[F]): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]] =
      transactionValidator
        .validate(transaction.signed)
        .map(_.errorMap(NonContextualValidationError))
        .flatMap {
          case Valid(_) =>
            if (shouldUseMptStore) {
              useMptStore(transaction)
            } else {
              useGlobalSnapshotInfo(transaction)
            }
          case Invalid(e) =>
            e.toNonEmptyList.asLeft[Hash].leftWiden[NonEmptyList[ContextualTransactionValidationError]].pure[F]
        }
  }
}
