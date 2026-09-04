package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.kernel.Async
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.snapshot.Validator.isNextSnapshot
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.schema.ID.IdOps
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, HasherSelector, SecurityProvider}
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

object LastNGlobalSnapshotStorage {

  def make[F[_]: Async: HasherSelector: SecurityProvider](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    snapshotSignerAllowlist: Option[Set[PeerId]] = None
  ): F[LastNGlobalSnapshotStorage[F] with LatestBalances[F]] = for {
    combinedSignalingRef <- SignallingRef.of[F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](None)
    incrementalSignalingRef <- SignallingRef.of[F, SortedMap[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]](SortedMap.empty)
  } yield make(lastGlobalSnapshotsSyncConfig, combinedSignalingRef, incrementalSignalingRef, snapshotSignerAllowlist)

  def make[F[_]: Async: HasherSelector: SecurityProvider](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    combinedSnapshotsR: SignallingRef[F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]],
    incrementalSnapshotsR: SignallingRef[F, SortedMap[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]]
  ): LastNGlobalSnapshotStorage[F] with LatestBalances[F] =
    make(lastGlobalSnapshotsSyncConfig, combinedSnapshotsR, incrementalSnapshotsR, None)

  def make[F[_]: Async: HasherSelector: SecurityProvider](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    combinedSnapshotsR: SignallingRef[F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]],
    incrementalSnapshotsR: SignallingRef[F, SortedMap[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]],
    snapshotSignerAllowlist: Option[Set[PeerId]]
  ): LastNGlobalSnapshotStorage[F] with LatestBalances[F] =
    new LastNGlobalSnapshotStorage[F] with LatestBalances[F] {
      private val logger = Slf4jLogger.getLoggerFromName(this.getClass.getName)

      private def setInitialInternal(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        state: GlobalSnapshotInfo,
        globalSnapshotFetcher: Option[Either[GlobalL0Service[F], SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]]],
        fetchGL0Function: Option[(Option[Hash], SnapshotOrdinal) => F[Signed[GlobalIncrementalSnapshot]]]
      ): F[Unit] = for {
        // Fetch and validate before installing either in-memory index. In particular, a missing
        // required sync target must leave initialization retryable in this process rather than
        // setting `combinedSnapshotsR` and making every later retry fail as "non empty".
        globalSnapshotsFetched <- fetchGlobalSnapshots(snapshot, globalSnapshotFetcher, fetchGL0Function)
        _ <- Async[F].uncancelable { _ =>
          validateAndSetInitialSnapshot(snapshot, state) >>
            updateIncrementalSnapshots(snapshot, globalSnapshotsFetched)
        }
      } yield ()

      private def validateAndSetInitialSnapshot(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        state: GlobalSnapshotInfo
      ): F[Unit] =
        combinedSnapshotsR.modify {
          case None => ((snapshot, state).some, Applicative[F].unit)
          case other =>
            (
              other,
              MonadThrow[F].raiseError[Unit](new Throwable(s"Failure setting initial snapshot! Encountered non empty storage"))
            )
        }.flatten

      private def fetchGlobalSnapshots(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        globalSnapshotFetcher: Option[Either[GlobalL0Service[F], SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]]],
        fetchGL0Function: Option[(Option[Hash], SnapshotOrdinal) => F[Signed[GlobalIncrementalSnapshot]]]
      ): F[List[Hashed[GlobalIncrementalSnapshot]]] =
        for {
          // Validate the supplied tip under its historical signing hasher before it can anchor
          // the descending walk. The walk is deliberately sequential: each trusted child names
          // the exact expected hash of its predecessor, so ordinal-only fetches and parallel
          // archive availability can never choose the lineage.
          validatedTip <- validateFetchedSnapshot(snapshot.signed, snapshot.hash.some)
          globalSnapshotsFetched <- fetchPredecessors(
            validatedTip,
            lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory.value,
            globalSnapshotFetcher,
            fetchGL0Function
          )

          requiredForSyncTarget = (0L to lastGlobalSnapshotsSyncConfig.syncOffset.value)
            .map(offset => SnapshotOrdinal.unsafeApply(Math.max(1L, snapshot.ordinal.value.value - offset)))
            .toSet
          available = (validatedTip :: globalSnapshotsFetched).iterator.map(_.ordinal).toSet
          missingRequired = requiredForSyncTarget -- available
          _ <- MonadThrow[F]
            .raiseError[Unit](
              new IllegalStateException(
                s"Recent Global snapshot window is missing sync-target ordinals=${missingRequired.toList.sorted.mkString(",")} " +
                  s"at parent=${snapshot.ordinal}; refusing incomplete startup window"
              )
            )
            .whenA((globalSnapshotFetcher.nonEmpty || fetchGL0Function.nonEmpty) && missingRequired.nonEmpty)

        } yield globalSnapshotsFetched

      private def fetchPredecessors(
        trustedChild: Hashed[GlobalIncrementalSnapshot],
        remaining: Int,
        globalSnapshotFetcher: Option[Either[GlobalL0Service[F], SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]]],
        fetchGL0Function: Option[(Option[Hash], SnapshotOrdinal) => F[Signed[GlobalIncrementalSnapshot]]]
      ): F[List[Hashed[GlobalIncrementalSnapshot]]] =
        if (remaining <= 0 || trustedChild.ordinal <= SnapshotOrdinal.MinIncrementalValue)
          List.empty[Hashed[GlobalIncrementalSnapshot]].pure[F]
        else {
          val predecessorOrdinal = SnapshotOrdinal.unsafeApply(trustedChild.ordinal.value.value - 1L)
          val expectedHash = trustedChild.signed.value.lastSnapshotHash

          fetchSingleSnapshot(
            predecessorOrdinal,
            expectedHash,
            globalSnapshotFetcher,
            fetchGL0Function
          ).flatMap {
            case None => List.empty[Hashed[GlobalIncrementalSnapshot]].pure[F]
            case Some(predecessor) =>
              MonadThrow[F]
                .raiseError[Unit](
                  new IllegalStateException(
                    s"Recent Global snapshot chain is non-contiguous: predecessor=${predecessor.ordinal}, " +
                      s"child=${trustedChild.ordinal}, expectedParentHash=$expectedHash, actualParentHash=${predecessor.hash}"
                  )
                )
                .unlessA(isNextSnapshot(predecessor, trustedChild.signed.value)) >>
                fetchPredecessors(
                  predecessor,
                  remaining - 1,
                  globalSnapshotFetcher,
                  fetchGL0Function
                ).map(predecessor :: _)
          }
        }

      private def validateFetchedSnapshot(
        snapshot: Signed[GlobalIncrementalSnapshot],
        expectedHash: Option[Hash]
      ): F[Hashed[GlobalIncrementalSnapshot]] =
        HasherSelector[F].forOrdinal(snapshot.ordinal) { implicit historicalHasher =>
          val signerIds = snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
          val uniqueSigners = signerIds.distinct.size === signerIds.size
          val allowedSigners = snapshotSignerAllowlist.forall(allowlist => signerIds.forall(allowlist.contains))

          for {
            hashed <- snapshot.toHashed[F]
            _ <- MonadThrow[F]
              .raiseError[Unit](
                new IllegalStateException(
                  s"Recent Global snapshot hash mismatch at ordinal=${snapshot.ordinal}: expected=${expectedHash.map(_.value)}, " +
                    s"actual=${hashed.hash.value}"
                )
              )
              .whenA(expectedHash.exists(_ =!= hashed.hash))
            _ <- MonadThrow[F]
              .raiseError[Unit](new IllegalStateException(s"Recent Global snapshot has duplicate signers at ordinal=${snapshot.ordinal}"))
              .unlessA(uniqueSigners)
            _ <- MonadThrow[F]
              .raiseError[Unit](
                new IllegalStateException(
                  s"Recent Global snapshot has a signer outside the configured seedlist at ordinal=${snapshot.ordinal}"
                )
              )
              .unlessA(allowedSigners)
            signaturesValid <- snapshot.hasValidSignature[F]
            _ <- MonadThrow[F]
              .raiseError[Unit](new IllegalStateException(s"Recent Global snapshot has invalid signatures at ordinal=${snapshot.ordinal}"))
              .unlessA(signaturesValid)
          } yield hashed
        }

      private def fetchSingleSnapshot(
        snapshotOrdinal: SnapshotOrdinal,
        expectedHash: Hash,
        globalSnapshotFetcher: Option[Either[GlobalL0Service[F], SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]]],
        fetchGL0Function: Option[(Option[Hash], SnapshotOrdinal) => F[Signed[GlobalIncrementalSnapshot]]]
      ): F[Option[Hashed[GlobalIncrementalSnapshot]]] = {
        val fetchFromFunction = fetchGL0Function.fold(Option.empty[Signed[GlobalIncrementalSnapshot]].pure[F]) { fetchFn =>
          fetchFn(expectedHash.some, snapshotOrdinal).map(_.some)
        }

        (globalSnapshotFetcher match {
          case Some(value) =>
            value match {
              case Left(globalL0Service) =>
                globalL0Service.pullGlobalSnapshot(snapshotOrdinal).map(_.map(_.signed))

              case Right(globalSnapshotStorage) =>
                for {
                  // The validated child, not an ordinal index, selects its parent.
                  // An abandoned same-ordinal branch must neither become authority
                  // nor prevent exact peer fallback when the declared hash is absent.
                  maybeSnapshot <- globalSnapshotStorage.get(expectedHash)
                  result <- maybeSnapshot match {
                    case Some(snapshot) =>
                      snapshot.some.pure[F]
                    case None => fetchFromFunction
                  }
                } yield result
            }
          // Global L0 recovery supplies the authenticated peer-fetch callback directly rather
          // than wrapping a GlobalL0Service or local SnapshotStorage. Ignoring it here left the
          // required sync-offset window empty and made every recovery attempt fail closed even
          // though peers served the exact ordinals.
          case None => fetchFromFunction
        }).flatMap(_.traverse(validateFetchedSnapshot(_, expectedHash.some)))
      }

      private def updateIncrementalSnapshots(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        globalSnapshotsFetched: List[Hashed[GlobalIncrementalSnapshot]]
      ): F[Unit] =
        incrementalSnapshotsR.modify { incrementalSnapshots =>
          val updated = (snapshot +: globalSnapshotsFetched).sortBy(_.ordinal.value.value).foldLeft(incrementalSnapshots) {
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

      def setForRecovery(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] =
        logger.info(s"[LastNGlobalSnapshotStorage] Recovery reset at ordinal=${snapshot.ordinal}") >>
          combinedSnapshotsR.set((snapshot, state).some) >>
          incrementalSnapshotsR.set(SortedMap(snapshot.ordinal -> snapshot))

      def clear: F[Unit] =
        logger.info("[LastNGlobalSnapshotStorage] Clearing for recovery download") >>
          combinedSnapshotsR.set(none) >>
          incrementalSnapshotsR.set(SortedMap.empty)

      def set(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): F[Unit] = for {
        _ <- combinedSnapshotsR.modify {
          case Some((current, _)) if isNextSnapshot(current, snapshot.signed.value) => ((snapshot, state).some, Applicative[F].unit)
          case s @ Some((current, _)) if current.hash === snapshot.hash             => (s, Applicative[F].unit)
          case other =>
            val current = other.map { case (value, _) => s"ordinal=${value.ordinal},hash=${value.hash}" }.getOrElse("empty")
            val incoming = s"ordinal=${snapshot.ordinal},hash=${snapshot.hash}"
            (
              other,
              MonadThrow[F].raiseError[Unit](
                new Throwable(s"Failure during setting new global snapshot: non-contiguous update current=[$current] incoming=[$incoming]")
              )
            )
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
            case Some((_, latest)) if latest.hash === snapshot.hash =>
              (incrementalSnapshots, Applicative[F].unit)
            case other =>
              val current = other.map { case (_, value) => s"ordinal=${value.ordinal},hash=${value.hash}" }.getOrElse("empty")
              val incoming = s"ordinal=${snapshot.ordinal},hash=${snapshot.hash}"
              (
                incrementalSnapshots,
                MonadThrow[F].raiseError[Unit](
                  new Throwable(
                    s"Failure during putting new global snapshot: non-contiguous update current=[$current] incoming=[$incoming]"
                  )
                )
              )
          }
        }.flatten
      } yield ()

      def get: F[Option[Hashed[GlobalIncrementalSnapshot]]] = getCombined.map(_.map { case (snapshot, _) => snapshot })

      def getCombined: F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = combinedSnapshotsR.get

      def getCombinedStream: fs2.Stream[F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        Stream
          .eval[F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](getCombined)
          .merge(combinedSnapshotsR.discrete)

      def getOrdinal: F[Option[SnapshotOrdinal]] =
        get.map(_.map(_.ordinal))

      def getHeight: F[Option[Height]] =
        get.map(_.map(_.height))

      def getLatestBalances: F[Option[Map[Address, Balance]]] =
        combinedSnapshotsR.get.map(_.map(_._2.balances.toMap))

      def getLatestBalancesStream: Stream[F, Map[Address, Balance]] =
        combinedSnapshotsR.discrete
          .evalMap(snapshotMap => Async[F].pure(snapshotMap.map(_._2)))
          .collect { case Some(snapshot) => snapshot }
          .map(_.balances)

      def getLastN: F[List[Hashed[GlobalIncrementalSnapshot]]] =
        incrementalSnapshotsR.get.map(_.values.toList)
    }
}
