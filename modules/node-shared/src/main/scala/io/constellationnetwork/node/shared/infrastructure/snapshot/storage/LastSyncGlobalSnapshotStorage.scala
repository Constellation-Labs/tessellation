package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.Ref
import cats.effect.kernel.Async
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.storage._
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSnapshotSync
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.snapshot.Validator.isNextSnapshot
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSnapshotStorage, LastSyncGlobalSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object LastSyncGlobalSnapshotStorage {

  /** Keep a small access-ordered window of exact historical contexts which have actually been used.
    *
    * Currency snapshots can intentionally carry the same signed GlobalSyncView for many rounds. A delayed Currency round must therefore
    * retain that exact value/context pair even after the rolling GL0 download window and filesystem checkpoint retention have advanced.
    * Four entries cover the active view plus recent recovery transitions without duplicating the much larger rolling GL0 window.
    */
  private val retainedExactCapacity = 4

  private[storage] def retainExact[A](
    retained: Vector[(SnapshotOrdinal, A)],
    ordinal: SnapshotOrdinal,
    value: A,
    capacity: Int = retainedExactCapacity
  ): Vector[(SnapshotOrdinal, A)] =
    (retained.filterNot(_._1 === ordinal) :+ (ordinal -> value)).takeRight(math.max(1, capacity))

  private[storage] def takeRetainedExact[A](
    retained: Vector[(SnapshotOrdinal, A)],
    ordinal: SnapshotOrdinal
  ): (Vector[(SnapshotOrdinal, A)], Option[A]) =
    retained.find(_._1 === ordinal) match {
      case Some((_, value)) => (retainExact(retained, ordinal, value), value.some)
      case None             => (retained, none)
    }

  def make[F[_]: Async](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    currencySnapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    globalSnapshotsWithStateLocalFileSystemStorage: GlobalSnapshotsWithStateLocalFileSystemStorage[F],
    globalSnapshotsWithStateDeltasLocalFileSystemStorage: GlobalSnapshotsWithStateDeltasLocalFileSystemStorage[F]
  ): F[LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LastSyncGlobalSnapshotStorage[F]] =
    (
      SignallingRef
        .of[F, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](SortedMap.empty),
      Ref.of[F, Vector[(SnapshotOrdinal, (GlobalIncrementalSnapshot, GlobalSnapshotInfo))]](Vector.empty)
    ).mapN {
      case (snapshotsR, retainedExactR) =>
        make[F](
          lastGlobalSnapshotsSyncConfig,
          snapshotsR,
          retainedExactR,
          currencySnapshotStorage,
          globalSnapshotsWithStateLocalFileSystemStorage,
          globalSnapshotsWithStateDeltasLocalFileSystemStorage
        )
    }

  def make[F[_]: Async](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    snapshotsR: SignallingRef[F, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]],
    retainedExactR: Ref[F, Vector[(SnapshotOrdinal, (GlobalIncrementalSnapshot, GlobalSnapshotInfo))]],
    currencySnapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    globalSnapshotsWithStateLocalFileSystemStorage: GlobalSnapshotsWithStateLocalFileSystemStorage[F],
    globalSnapshotsWithStateDeltasLocalFileSystemStorage: GlobalSnapshotsWithStateDeltasLocalFileSystemStorage[F]
  ): LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LastSyncGlobalSnapshotStorage[F] =
    new LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LastSyncGlobalSnapshotStorage[F] {
      val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

      def getGlobalSnapshotsWithStateDeltas(ordinal: SnapshotOrdinal): F[Option[GlobalSnapshotWithStateDeltas]] =
        snapshotsR.get.map(_.get(ordinal)).flatMap {
          case Some(snapshotWithState) =>
            logger.info(s"Getting global snapshots with state deltas of ordinal $ordinal from memory") >>
              GlobalSnapshotWithStateDeltas(
                snapshotWithState._1.signed,
                snapshotWithState._2.activeAllowSpends,
                snapshotWithState._2.activeTokenLocks
              ).some.pure[F]
          case None =>
            logger.info(s"Trying to load global snapshots with state deltas of $ordinal from file system") >>
              globalSnapshotsWithStateDeltasLocalFileSystemStorage
                .read(ordinal)
        }

      private def retainExact(
        ordinal: SnapshotOrdinal,
        combined: (GlobalIncrementalSnapshot, GlobalSnapshotInfo)
      ): F[Unit] =
        retainedExactR.update(LastSyncGlobalSnapshotStorage.retainExact(_, ordinal, combined))

      private def getRetainedExact(ordinal: SnapshotOrdinal): F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]] =
        retainedExactR.modify(LastSyncGlobalSnapshotStorage.takeRetainedExact(_, ordinal))

      def getCombined(ordinal: SnapshotOrdinal): F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]] =
        snapshotsR.get.map(_.get(ordinal)).flatMap {
          case Some(snapshotWithState) =>
            val combined = (snapshotWithState._1.signed.value, snapshotWithState._2)
            logger.info(s"Getting ordinal $ordinal from memory") >> retainExact(ordinal, combined).as(combined.some)
          case None =>
            getRetainedExact(ordinal).flatMap {
              case Some(combined) =>
                logger.info(s"Getting retained exact ordinal $ordinal from memory").as(combined.some)
              case None =>
                logger.info(s"Trying to load $ordinal from file system") >>
                  globalSnapshotsWithStateLocalFileSystemStorage
                    .read(ordinal)
                    .map(_.map(value => (value.snapshot.value, value.state)))
                    .flatTap(_.traverse_(retainExact(ordinal, _)))
            }
        }

      def set(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        snapshotsR.modify { snapshots =>
          snapshots.lastOption match {
            case Some((_, (latest, _))) if isNextSnapshot(latest, snapshot.signed.value) =>
              val updated = snapshots.updated(snapshot.ordinal, (snapshot, state))
              val trimmed = if (updated.size > lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory) {
                updated.takeRight(lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory)
              } else {
                updated
              }
              (trimmed, Applicative[F].unit)
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

      def setForRecovery(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        logger.info(s"[LastSyncGlobalSnapshotStorage] Recovery reset at ordinal=${snapshot.ordinal}") >>
          retainedExactR.set(Vector.empty) >>
          snapshotsR.set(SortedMap(snapshot.ordinal -> (snapshot, state)))

      def clear: F[Unit] =
        logger.info("[LastSyncGlobalSnapshotStorage] Clearing for recovery download") >>
          retainedExactR.set(Vector.empty) >>
          snapshotsR.set(SortedMap.empty)

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

      private def processOrdinals(
        peersToGetSnapshotOrdinalSync: SortedMap[peer.PeerId, Signed[GlobalSnapshotSync]],
        offset: Long
      ): F[Option[SnapshotOrdinal]] = {
        val ordinalGroups = peersToGetSnapshotOrdinalSync.values
          .map(_.globalSnapshotOrdinal)
          .groupBy(identity)

        ordinalGroups.maxByOption { case (ordinal, occurrences) => (occurrences.size, ordinal.value.value) }.fold {
          logger.warn("No valid ordinal found, returning None") >>
            none[SnapshotOrdinal].pure[F]
        } {
          case (ordinal, occurrences) =>
            val targetOrdinal = ordinal.value - offset
            for {
              _ <- logger.info(s"Selected ordinal ${ordinal.value.value} with ${occurrences.size} occurrences")
              _ <- logger.info(s"Target ordinal after offset: $targetOrdinal")
              result <- SnapshotOrdinal(targetOrdinal) match {
                case Some(validOrdinal) =>
                  logger.info(s"Getting combined snapshot for ordinal: ${validOrdinal.value.value}") >>
                    validOrdinal.some.pure[F]
                case None =>
                  logger.warn(s"Invalid ordinal after offset calculation: $targetOrdinal") >>
                    none[SnapshotOrdinal].pure[F]
              }
            } yield result
        }
      }

      private def getLastSyncOrdinal: F[Option[SnapshotOrdinal]] =
        currencySnapshotStorage.head.flatMap { optionValue =>
          optionValue.flatTraverse {
            case (snapshot, info) =>
              val offset = lastGlobalSnapshotsSyncConfig.syncOffset
              val lastPeersParticipatedOnConsensus = snapshot.proofs.map(_.id.toPeerId)

              info.globalSnapshotSyncView match {
                case Some(value) =>
                  val filtered = value.filter {
                    case (peerId, _) =>
                      lastPeersParticipatedOnConsensus.contains(peerId)
                  }
                  processOrdinals(filtered, offset)
                case None =>
                  logger.warn("No globalSnapshotSyncView available") >>
                    none[SnapshotOrdinal].pure[F]
              }
          }
        }

      override def getLastSynchronizedCombined: F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]] = for {
        maybeLastSyncOrdinal <- getLastSyncOrdinal
        result <- maybeLastSyncOrdinal match {
          case Some(value) => getCombined(value)
          case None        => none.pure[F]
        }
      } yield result

      override def getLastSynchronized: F[Option[GlobalIncrementalSnapshot]] = for {
        maybeLastSyncOrdinal <- getLastSyncOrdinal
        result <- maybeLastSyncOrdinal match {
          case Some(value) => getGlobalSnapshotsWithStateDeltas(value).map(_.map(_.snapshot.value))
          case None        => none.pure[F]
        }
      } yield result

      override def getLastSynchronizedActiveAllowSpends
        : F[Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]] = for {
        maybeLastSyncOrdinal <- getLastSyncOrdinal
        result <- maybeLastSyncOrdinal match {
          case Some(value) => getGlobalSnapshotsWithStateDeltas(value).map(_.map(_.activeAllowSpends).getOrElse(none))
          case None        => none.pure[F]
        }
      } yield result

      override def getLastSynchronizedActiveTokenLocks: F[Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]] = for {
        maybeLastSyncOrdinal <- getLastSyncOrdinal
        result <- maybeLastSyncOrdinal match {
          case Some(value) => getGlobalSnapshotsWithStateDeltas(value).map(_.map(_.activeTokenLocks).getOrElse(none))
          case None        => none.pure[F]
        }
      } yield result
    }
}
