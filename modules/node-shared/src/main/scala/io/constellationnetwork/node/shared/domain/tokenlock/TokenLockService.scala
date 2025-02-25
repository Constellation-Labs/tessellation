package io.constellationnetwork.node.shared.domain.tokenlock

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.ext.cats.syntax.validated.validatedSyntax
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.tokenlock.ContextualTokenLockValidator.{
  ContextualTokenLockValidationError,
  NonContextualValidationError
}
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{Hashed, Hasher}

import fs2.Stream

trait TokenLockService[F[_]] {
  def offer(tokenLock: Hashed[TokenLock])(implicit hasher: Hasher[F]): F[Either[NonEmptyList[ContextualTokenLockValidationError], Hash]]
}

object TokenLockService {
  def make[F[_]: Async, P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P]](
    tokenLockStorage: TokenLockStorage[F],
    lastSnapshotStorage: LastSnapshotStorage[F, S, SI] with LatestBalances[F],
    tokenLockValidator: TokenLockValidator[F]
  ): TokenLockService[F] = new TokenLockService[F] {

    def offer(
      tokenLock: Hashed[TokenLock]
    )(implicit hasher: Hasher[F]): F[Either[NonEmptyList[ContextualTokenLockValidationError], Hash]] =
      tokenLockValidator
        .validate(tokenLock.signed)
        .map(_.errorMap(NonContextualValidationError))
        .flatMap {
          case Valid(_) =>
            lastSnapshotStorage.get.map {
              case Some(snapshot) =>
                snapshot.signed.value match {
                  case cis: CurrencyIncrementalSnapshot =>
                    cis.globalSyncView.map(_.epochProgress).getOrElse(EpochProgress.MinValue)
                  case gis: GlobalIncrementalSnapshot =>
                    gis.epochProgress
                  case _ =>
                    EpochProgress.MinValue
                }
              case None =>
                EpochProgress.MinValue
            }.flatMap { lastGlobalEpochProgress =>
              lastSnapshotStorage.getCombinedStream.map {
                case Some((s, si)) => (s.ordinal, si.balances.getOrElse(tokenLock.source, Balance.empty))
                case None          => (SnapshotOrdinal.MinValue, Balance.empty)
              }.changes.switchMap {
                case (latestOrdinal, balance) =>
                  Stream.eval(tokenLockStorage.tryPut(tokenLock, latestOrdinal, lastGlobalEpochProgress, balance))
              }.head.compile.last.flatMap {
                case Some(value) => value.pure[F]
                case None =>
                  new Exception(s"Unexpected state, stream should always emit the first snapshot")
                    .raiseError[F, Either[NonEmptyList[ContextualTokenLockValidationError], Hash]]
              }
            }
          case Invalid(e) =>
            e.toNonEmptyList.asLeft[Hash].leftWiden[NonEmptyList[ContextualTokenLockValidationError]].pure[F]
        }
  }
}
