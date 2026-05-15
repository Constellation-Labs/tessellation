package io.constellationnetwork.node.shared.domain.tokenlock

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.ext.cats.syntax.validated.validatedSyntax
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.tokenlock.ContextualTokenLockValidator.{
  ContextualTokenLockValidationError,
  NonContextualValidationError
}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

import fs2.Stream

trait TokenLockService[F[_]] {
  def offer(tokenLock: Hashed[TokenLock])(implicit hasher: Hasher[F]): F[Either[NonEmptyList[ContextualTokenLockValidationError], Hash]]
}

object TokenLockService {
  def make[F[_]: Async, P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P]](
    tokenLockStorage: TokenLockStorage[F],
    lastSnapshotStorage: LastSnapshotStorage[F, S, SI] with LatestBalances[F],
    tokenLockValidator: TokenLockValidator[F],
    maybeMptStore: Option[MptStore[F, GlobalStateKey]] = None
  ): TokenLockService[F] = new TokenLockService[F] {

    import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._

    private def getBalanceAndTokenLocks(
      si: SI,
      address: Address
    ): F[(Balance, SortedSet[Signed[TokenLock]])] =
      maybeMptStore match {
        case Some(mptStore) =>
          for {
            balance <- mptStore.getBalance(address).map(_.getOrElse(Balance.empty))
            tokenLocks <- mptStore.getActiveTokenLocks(address).map(_.getOrElse(SortedSet.empty[Signed[TokenLock]]))
          } yield (balance, tokenLocks)
        case None =>
          (
            si.balances.getOrElse(address, Balance.empty),
            si.getActiveTokenLocks.getOrElse(address, SortedSet.empty[Signed[TokenLock]])
          ).pure[F]
      }

    def offer(
      tokenLock: Hashed[TokenLock]
    )(implicit hasher: Hasher[F]): F[Either[NonEmptyList[ContextualTokenLockValidationError], Hash]] =
      for {
        lastGlobalEpochProgress <- lastSnapshotStorage.get.map {
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
        }
        result <- tokenLockValidator
          .validate(tokenLock.signed, lastGlobalEpochProgress.some)
          .map(_.errorMap(NonContextualValidationError))
          .flatMap {
            case Valid(_) =>
              lastSnapshotStorage.getCombinedStream.evalMap {
                case Some((s, si)) =>
                  getBalanceAndTokenLocks(si, tokenLock.source).map {
                    case (balance, activeTokenLocks) => (s.ordinal, balance, activeTokenLocks)
                  }
                case None => (SnapshotOrdinal.MinValue, Balance.empty, SortedSet.empty[Signed[TokenLock]]).pure[F]
              }.changes.switchMap {
                case (latestOrdinal, balance, activeTokenLocks) =>
                  Stream.eval(tokenLockStorage.tryPut(tokenLock, latestOrdinal, lastGlobalEpochProgress, balance, activeTokenLocks))
              }.head.compile.last.flatMap {
                case Some(value) => value.pure[F]
                case None =>
                  new Exception(s"Unexpected state, stream should always emit the first snapshot")
                    .raiseError[F, Either[NonEmptyList[ContextualTokenLockValidationError], Hash]]
              }

            case Invalid(e) =>
              e.toNonEmptyList.asLeft[Hash].leftWiden[NonEmptyList[ContextualTokenLockValidationError]].pure[F]
          }
      } yield result
  }
}
