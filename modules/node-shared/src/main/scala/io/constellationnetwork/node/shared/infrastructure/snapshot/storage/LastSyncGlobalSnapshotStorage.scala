package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.kernel.Async
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.snapshot.Validator.isNextSnapshot
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSnapshotStorage, LastSyncGlobalSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.schema._
import io.constellationnetwork.security.Hashed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import fs2.Stream
import fs2.concurrent.SignallingRef

object LastSyncGlobalSnapshotStorage {
  def make[F[_]: Async](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    currencySnapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
  ): F[LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LastSyncGlobalSnapshotStorage[F]] =
    SignallingRef
      .of[F, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](SortedMap.empty)
      .map(make[F](lastGlobalSnapshotsSyncConfig, _, currencySnapshotStorage))

  def make[F[_]: Async](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    snapshotsR: SignallingRef[F, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]],
    currencySnapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
  ): LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LastSyncGlobalSnapshotStorage[F] =
    new LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LastSyncGlobalSnapshotStorage[F] {

      private def deleteBelow(ordinal: SnapshotOrdinal): F[Unit] = snapshotsR.update {
        _.filterNot { case (key, _) => key < ordinal }
      }

      def getCombined(ordinal: SnapshotOrdinal): F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        snapshotsR.get.map(_.get(ordinal))

      def get(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalIncrementalSnapshot]]] =
        snapshotsR.get.map(_.get(ordinal).map { case (snapshot, _) => snapshot })

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

      def getAll: F[Option[List[Hashed[GlobalIncrementalSnapshot]]]] =
        snapshotsR.get.map(_.values.map { case (snapshot, _) => snapshot }.toList.some)

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
              val offset = lastGlobalSnapshotsSyncConfig.syncOffset

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

      override def getLastNSynchronized(
        n: Int
      ): F[Option[List[Hashed[GlobalIncrementalSnapshot]]]] =
        currencySnapshotStorage.head.flatMap {
          case Some((_, info)) =>
            val maybeSnapshots: F[Option[List[Hashed[GlobalIncrementalSnapshot]]]] =
              info.globalSnapshotSyncView.fold(
                Async[F].pure(Option.empty[List[Hashed[GlobalIncrementalSnapshot]]])
              ) { syncView =>
                val mostFrequentOrdinal = syncView.values
                  .map(_.globalSnapshotOrdinal)
                  .groupBy(identity)
                  .toList
                  .sortBy { case (ordinal, occurrences) => (-occurrences.size, ordinal.value.value) }
                  .map { case (ordinal, _) => ordinal }
                  .headOption

                mostFrequentOrdinal match {
                  case Some(lastOrdinal) =>
                    val selectedOrdinals = (0 until n).flatMap { i =>
                      val value = lastOrdinal.value.value - i.toLong
                      NonNegLong
                        .from(value)
                        .toOption
                        .flatMap(SnapshotOrdinal(_))
                    }.toList

                    selectedOrdinals.traverse(ordinal => get(ordinal)).map { maybeSnapshots =>
                      val filteredSnapshots = maybeSnapshots.flatten
                      if (filteredSnapshots.isEmpty) None else Some(filteredSnapshots)
                    }

                  case None =>
                    Async[F].pure(None)
                }
              }
            maybeSnapshots.flatMap {
              case None => getAll
              case some => Async[F].pure(some)
            }

          case None =>
            getAll
        }

      def deleteOlderThanSynchronized(): F[Unit] = getLastSynchronizedCombined
        .flatMap(_.traverse {
          case (snapshot, _) =>
            val deleteOffset = lastGlobalSnapshotsSyncConfig.minGlobalSnapshotsToParticipateConsensus
            NonNegLong.from(snapshot.ordinal.value - deleteOffset) match {
              case Left(_)      => deleteBelow(SnapshotOrdinal.MinValue)
              case Right(value) => deleteBelow(SnapshotOrdinal(value))
            }
        })
        .void
    }
}
