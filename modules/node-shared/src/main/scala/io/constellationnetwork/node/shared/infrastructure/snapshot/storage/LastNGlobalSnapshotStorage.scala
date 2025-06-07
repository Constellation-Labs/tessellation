package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.kernel.Async
import cats.syntax.all._
import cats.{Applicative, MonadThrow, Parallel}

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.snapshot.Validator.isNextSnapshot
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

object LastNGlobalSnapshotStorage {

  def make[F[_]: Async: Hasher: Parallel](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig
  ): F[LastNGlobalSnapshotStorage[F] with LatestBalances[F]] = for {
    combinedSignalingRef <- SignallingRef
      .of[F, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](SortedMap.empty)
    incrementalSignalingRef <- SignallingRef.of[F, SortedMap[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]](SortedMap.empty)
  } yield make(lastGlobalSnapshotsSyncConfig, combinedSignalingRef, incrementalSignalingRef)

  def make[F[_]: Async: Hasher: Parallel](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    combinedSnapshotsR: SignallingRef[F, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]],
    incrementalSnapshotsR: SignallingRef[F, SortedMap[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]]
  ): LastNGlobalSnapshotStorage[F] with LatestBalances[F] =
    new LastNGlobalSnapshotStorage[F] with LatestBalances[F] {
      private val logger = Slf4jLogger.getLoggerFromName("LastNGlobalSnapshotStorage")

      private def setInitialInternal(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        state: GlobalSnapshotInfo,
        globalSnapshotFetcher: Option[Either[GlobalL0Service[F], SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]]],
        fetchGL0Function: Option[(Option[Hash], SnapshotOrdinal) => F[Signed[GlobalIncrementalSnapshot]]]
      ): F[Unit] = for {
        _ <- validateAndSetInitialSnapshot(snapshot, state)
        _ <- fetchAndStoreGlobalSnapshots(snapshot, globalSnapshotFetcher, fetchGL0Function)
      } yield ()

      private def validateAndSetInitialSnapshot(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        state: GlobalSnapshotInfo
      ): F[Unit] =
        combinedSnapshotsR.modify { snapshots =>
          if (snapshots.nonEmpty)
            (snapshots, MonadThrow[F].raiseError[Unit](new Throwable("Failure putting initial snapshot! Storage non empty.")))
          else
            (snapshots.updated(snapshot.ordinal, (snapshot, state)), Applicative[F].unit)
        }.flatten

      private def fetchAndStoreGlobalSnapshots(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        globalSnapshotFetcher: Option[Either[GlobalL0Service[F], SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]]],
        fetchGL0Function: Option[(Option[Hash], SnapshotOrdinal) => F[Signed[GlobalIncrementalSnapshot]]]
      ): F[Unit] = {
        val ordinalsToFetch = (0 to lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory.value)
          .map(i => Math.max(1, snapshot.ordinal.value.value - i))
          .distinct
          .toList

        for {
          globalSnapshotsFetched <- ordinalsToFetch
            .parTraverse(ordinal =>
              fetchSingleSnapshot(
                SnapshotOrdinal.unsafeApply(ordinal),
                globalSnapshotFetcher,
                fetchGL0Function
              )
            )
            .map(_.flatten)
            .map(_.sortBy(_.ordinal.value.value))

          _ <- updateIncrementalSnapshots(snapshot, globalSnapshotsFetched)
        } yield ()
      }

      private def fetchSingleSnapshot(
        snapshotOrdinal: SnapshotOrdinal,
        globalSnapshotFetcher: Option[Either[GlobalL0Service[F], SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]]],
        fetchGL0Function: Option[(Option[Hash], SnapshotOrdinal) => F[Signed[GlobalIncrementalSnapshot]]]
      ): F[Option[Hashed[GlobalIncrementalSnapshot]]] =
        globalSnapshotFetcher match {
          case Some(value) =>
            value match {
              case Left(globalL0Service) =>
                globalL0Service.pullGlobalSnapshot(snapshotOrdinal)

              case Right(globalSnapshotStorage) =>
                for {
                  maybeSnapshot <- globalSnapshotStorage.get(snapshotOrdinal)
                  result <- maybeSnapshot match {
                    case Some(snapshot) =>
                      snapshot.toHashed.map(_.some)
                    case None =>
                      fetchGL0Function match {
                        case Some(fetchFn) => fetchFn(none, snapshotOrdinal).flatMap(_.toHashed.map(_.some))
                        case None          => Option.empty[Hashed[GlobalIncrementalSnapshot]].pure[F]
                      }
                  }
                } yield result
            }
          case None => none[Hashed[GlobalIncrementalSnapshot]].pure[F]
        }

      private def updateIncrementalSnapshots(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        globalSnapshotsFetched: List[Hashed[GlobalIncrementalSnapshot]]
      ): F[Unit] =
        incrementalSnapshotsR.modify { incrementalSnapshots =>
          val updated = (snapshot +: globalSnapshotsFetched).foldLeft(incrementalSnapshots) {
            case (acc, current) => acc.updated(current.ordinal, current)
          }
          (updated, Applicative[F].unit)
        }.flatten

      def setInitial(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        state: GlobalSnapshotInfo
      ): F[Unit] =
        setInitialInternal(snapshot, state, None, None)

      def setInitialFetchingGL0(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        state: GlobalSnapshotInfo,
        globalSnapshotFetcher: Option[GlobalFetcher],
        fetchGL0Function: Option[FetchFunction]
      ): F[Unit] =
        logger.info("Filling lastNGlobalSnapshots, this might need to download more snapshots from the network") >>
          setInitialInternal(snapshot, state, globalSnapshotFetcher, fetchGL0Function)

      def set(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] = for {
        _ <- combinedSnapshotsR.modify { combinedSnapshots =>
          combinedSnapshots.lastOption match {
            case Some((_, (latest, _))) if isNextSnapshot(latest, snapshot.signed.value) =>
              (combinedSnapshots.updated(snapshot.ordinal, (snapshot, state)), Applicative[F].unit)
            case _ => (combinedSnapshots, MonadThrow[F].raiseError[Unit](new Throwable("Failure during putting new global snapshot!")))
          }
        }.flatten
        _ <- incrementalSnapshotsR.modify { incrementalSnapshots =>
          incrementalSnapshots.lastOption match {
            case Some((_, latest)) if isNextSnapshot(latest, snapshot.signed.value) =>
              val maxLastGlobalSnapshotsInMemory = lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory.value
              val updated = incrementalSnapshots.updated(snapshot.ordinal, snapshot)
              val trimmed =
                if (updated.size > maxLastGlobalSnapshotsInMemory)
                  updated.toSeq.sortBy(_._1.value.value).takeRight(maxLastGlobalSnapshotsInMemory).toSortedMap
                else
                  updated
              (trimmed, Applicative[F].unit)
            case _ =>
              (incrementalSnapshots, MonadThrow[F].raiseError[Unit](new Throwable("Failure during putting new global snapshot!")))
          }
        }.flatten
      } yield ()

      def get: F[Option[Hashed[GlobalIncrementalSnapshot]]] = getCombined.map(_.map { case (snapshot, _) => snapshot })

      def getCombined: F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = combinedSnapshotsR.get.map {
        _.lastOption.map { case (_, combined) => combined }
      }

      def getCombinedStream: fs2.Stream[F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        Stream
          .eval[F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](getCombined)
          .merge(combinedSnapshotsR.discrete.map(_.lastOption.map { case (_, combined) => combined }))

      def getOrdinal: F[Option[SnapshotOrdinal]] =
        get.map(_.map(_.ordinal))

      def getHeight: F[Option[Height]] =
        get.map(_.map(_.height))

      def getLatestBalances: F[Option[Map[Address, Balance]]] =
        combinedSnapshotsR.get.map(_.headOption.map(_._2._2.balances.toMap))

      def getLatestBalancesStream: Stream[F, Map[Address, Balance]] =
        combinedSnapshotsR.discrete
          .evalMap(snapshotMap => Async[F].pure(snapshotMap.headOption.map(_._2)))
          .collect { case Some(snapshot) => snapshot }
          .map(_._2.balances)

      def getLastN: F[List[Hashed[GlobalIncrementalSnapshot]]] =
        incrementalSnapshotsR.get.map(_.values.toList)
    }
}
