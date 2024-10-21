package io.constellationnetwork.currency.l0.snapshot.storage

import cats.effect.kernel.Async
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.node.shared.domain.snapshot.Validator.isNextSnapshot
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.schema._
import io.constellationnetwork.security.Hashed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import fs2.Stream
import fs2.concurrent.SignallingRef

trait LastSynchronizedGlobalSnapshotStorage[F[_]] {
  def getLastSynchronizedCombined: F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]
  def deleteOlderThanSynchronized(): F[Unit]
}

object LastSynchronizedGlobalSnapshotStorage {
  def make[F[_]: Async](
    currencySnapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
  ): F[LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LastSynchronizedGlobalSnapshotStorage[F]] =
    SignallingRef
      .of[F, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](SortedMap.empty)
      .map(make[F](_, currencySnapshotStorage))

  def make[F[_]: Async](
    snapshotsR: SignallingRef[F, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]],
    currencySnapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
  ): LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LastSynchronizedGlobalSnapshotStorage[F] =
    new LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LastSynchronizedGlobalSnapshotStorage[F] {

      private def deleteBelow(ordinal: SnapshotOrdinal): F[Unit] = snapshotsR.update {
        _.filterNot { case (key, _) => key < ordinal }
      }

      def getCombined(ordinal: SnapshotOrdinal): F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        snapshotsR.get.map(_.get(ordinal))

      def set(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        snapshotsR.modify { snapshots =>
          snapshots.lastOption match {
            case Some((_, (latest, _))) if isNextSnapshot(latest, snapshot.signed.value) =>
              (snapshots.updated(snapshot.ordinal, (snapshot, state)), Applicative[F].unit)
            case _ => (snapshots, MonadThrow[F].raiseError[Unit](new Throwable("Failure during putting new global snapshot!")))
          }
        }.flatten

      def setInitial(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        snapshotsR.modify { snapshots =>
          if (snapshots.nonEmpty) {
            (snapshots, MonadThrow[F].raiseError[Unit](new Throwable(s"Failure putting initial snapshot! Storage non empty.")))
          } else {
            (snapshots.updated(snapshot.ordinal, (snapshot, state)), Applicative[F].unit)
          }
        }.flatten

      def get: F[Option[Hashed[GlobalIncrementalSnapshot]]] = getCombined.map(_.map { case (snapshot, _) => snapshot })

      def getCombined: F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = snapshotsR.get.map {
        _.lastOption.map { case (_, combined) => combined }
      }

      def getCombinedStream: fs2.Stream[F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        Stream
          .eval[F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](getCombined)
          .merge(snapshotsR.discrete.map(_.lastOption.map { case (_, combined) => combined }))

      def getOrdinal: F[Option[SnapshotOrdinal]] = get.map(_.map(_.ordinal))

      def getHeight: F[Option[height.Height]] = get.map(_.map(_.height))

      def getLastSynchronizedCombined: F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        currencySnapshotStorage.head.flatMap {
          _.flatTraverse {
            case (_, info) =>
              val offset = NonNegLong(2L)

              info.globalSnapshotSyncView.flatTraverse {
                _.values
                  .map(_.globalSnapshotOrdinal)
                  .groupBy(identity)
                  .maxByOption { case (ordinal, occurrences) => (occurrences.size, -ordinal.value.value) }
                  .flatMap { case (ordinal, _) => SnapshotOrdinal(ordinal.value - offset) }
                  .flatTraverse(getCombined)
              }
          }
        }

      def deleteOlderThanSynchronized(): F[Unit] = getLastSynchronizedCombined
        .flatMap(_.traverse {
          case (snapshot, _) => deleteBelow(snapshot.ordinal)
        })
        .void
    }
}
