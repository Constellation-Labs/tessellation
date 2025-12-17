package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Parallel
import cats.data.{NonEmptyChain, OptionT}
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l0.StoragesInitializer.initializeStorages
import io.constellationnetwork.ext.cats.syntax.partialPrevious.catsSyntaxPartialPrevious
import io.constellationnetwork.merkletree.StateProofValidator
import io.constellationnetwork.node.shared.domain.snapshot.SnapshotContextFunctions
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.schema._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotTraverse[F[_]] {
  def loadChain(): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])]
}

object GlobalSnapshotTraverse {

  type IncLoadResult = Either[Signed[GlobalIncrementalSnapshotV2], Signed[GlobalIncrementalSnapshot]]
  type FullOrIncResult = Either[IncLoadResult, Signed[GlobalSnapshot]]

  private case class RollbackSnapshotNotFound(h: Hash) extends NoStackTrace {
    override def getMessage: String = s"Rollback snapshot with hash=${h.show} not found!"
  }

  def make[F[_]: Async: Parallel: HasherSelector](
    loadInc: Hash => F[Option[Signed[GlobalIncrementalSnapshot]]],
    loadIncV2: Hash => F[Option[Signed[GlobalIncrementalSnapshotV2]]],
    loadFull: Hash => F[Option[Signed[GlobalSnapshot]]],
    loadInfo: SnapshotOrdinal => F[Option[GlobalSnapshotInfo]],
    loadInfoV3: SnapshotOrdinal => F[Option[GlobalSnapshotInfoV3]],
    contextFns: SnapshotContextFunctions[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    rollbackHash: Hash,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    globalSnapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    download: Download[F, GlobalIncrementalSnapshot]
  ): GlobalSnapshotTraverse[F] =
    new GlobalSnapshotTraverse[F] {
      private implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

      // ===== Basic Loaders (OptionT for clean chaining) =====

      private def tryLoadCurrentInc(h: Hash): OptionT[F, Signed[GlobalIncrementalSnapshot]] =
        OptionT(loadInc(h)).semiflatTap(_ => logger.debug(s"Loaded current incremental for hash=${h.show}"))

      private def tryLoadV2Inc(h: Hash): OptionT[F, Signed[GlobalIncrementalSnapshotV2]] =
        OptionT(loadIncV2(h)).semiflatTap(_ => logger.debug(s"Loaded V2 incremental for hash=${h.show}"))

      private def tryLoadFull(h: Hash): OptionT[F, Signed[GlobalSnapshot]] =
        OptionT(loadFull(h)).semiflatTap(_ => logger.debug(s"Loaded full snapshot for hash=${h.show}"))

      // ===== Combined Loaders =====

      private def loadIncOrV2OrErr(h: Hash): F[IncLoadResult] = {
        val tryCurrent = tryLoadCurrentInc(h).map(_.asRight[Signed[GlobalIncrementalSnapshotV2]])
        val tryV2 = tryLoadV2Inc(h).map(_.asLeft[Signed[GlobalIncrementalSnapshot]])

        logger.debug(s"Loading incremental snapshot for hash=${h.show}") >>
          tryCurrent
            .orElse(tryV2)
            .getOrElseF(
              logger.error(s"Incremental snapshot not found for hash=${h.show}") >>
                new Exception(s"Incremental snapshot not found (tried current and V2), hash=${h.show}")
                  .raiseError[F, IncLoadResult]
            )
      }

      private def loadFullOrIncOrErr(h: Hash): F[FullOrIncResult] = {
        val full = tryLoadFull(h).map(_.asRight[IncLoadResult])
        val currentInc = tryLoadCurrentInc(h).map(inc => inc.asRight[Signed[GlobalIncrementalSnapshotV2]].asLeft[Signed[GlobalSnapshot]])
        val v2Inc = tryLoadV2Inc(h).map(v2 => v2.asLeft[Signed[GlobalIncrementalSnapshot]].asLeft[Signed[GlobalSnapshot]])

        logger.debug(s"Loading snapshot for hash=${h.show}") >>
          full
            .orElse(currentInc)
            .orElse(v2Inc)
            .getOrElseF(
              logger.error(s"No snapshot found for hash=${h.show}") >>
                new Exception(s"Found neither full nor incremental snapshot for hash=${h.show}")
                  .raiseError[F, FullOrIncResult]
            )
      }

      // ===== Info Loaders =====

      private def loadInfoOrErr(o: SnapshotOrdinal): F[GlobalSnapshotInfo] =
        loadInfo(o).flatMap(_.liftTo[F](new Exception(s"SnapshotInfo not found, ordinal=${o.show}")))

      private def loadInfoV3OrErr(o: SnapshotOrdinal): F[GlobalSnapshotInfoV3] =
        loadInfoV3(o).flatMap(_.liftTo[F](new Exception(s"SnapshotInfoV3 not found, ordinal=${o.show}")))

      private def loadInfoForSnapshot(result: FullOrIncResult): F[GlobalSnapshotInfo] =
        result match {
          case Right(full)          => GlobalSnapshotInfoV1.toGlobalSnapshotInfo(full.info).pure[F]
          case Left(Right(current)) => loadInfoOrErr(current.ordinal)
          case Left(Left(v2))       => loadInfoOrErr(v2.ordinal)
        }

      // ===== Validation =====

      private def validateCurrentFormat(
        snapshot: Signed[GlobalIncrementalSnapshot],
        info: GlobalSnapshotInfo
      ): F[(Hashed[GlobalIncrementalSnapshot], Signed[GlobalIncrementalSnapshot])] =
        for {
          proof <- HasherSelector[F].withCurrent(implicit h => info.stateProofFor(h.getLogic(snapshot.ordinal), snapshot.ordinal))
          hashed <- HasherSelector[F].withCurrent(implicit h => snapshot.toHashed)
          isInvalid <- StateProofValidator.validate(hashed, proof).map(_.isInvalid)
          _ <- new Exception(s"Snapshot info mismatch at ordinal=${snapshot.ordinal.show}").raiseError[F, Unit].whenA(isInvalid)
        } yield (hashed, snapshot)

      private def validateV2Format(
        snapshot: Signed[GlobalIncrementalSnapshotV2]
      ): F[(Hashed[GlobalIncrementalSnapshot], Signed[GlobalIncrementalSnapshot])] =
        for {
          infoV3 <- loadInfoV3OrErr(snapshot.ordinal)
          proof <- HasherSelector[F].withCurrent(implicit h => infoV3.stateProof(snapshot.ordinal))
          hashedV2 <- HasherSelector[F].withCurrent(implicit h => snapshot.toHashed)
          isInvalid <- StateProofValidator.validate(hashedV2, proof).map(_.isInvalid)
          _ <- new Exception(s"Snapshot info (V2) mismatch at ordinal=${snapshot.ordinal.show}").raiseError[F, Unit].whenA(isInvalid)
          current = snapshot.map(_.toGlobalIncrementalSnapshot)
          hashed <- HasherSelector[F].withCurrent(implicit h => current.toHashed)
        } yield (hashed, current)

      private def validateFirstSnapshot(
        incEither: IncLoadResult,
        info: GlobalSnapshotInfo
      ): F[(Hashed[GlobalIncrementalSnapshot], Signed[GlobalIncrementalSnapshot])] =
        incEither.fold(validateV2Format, validateCurrentFormat(_, info))

      // ===== Process =====

      private def updateStoragesIfNeeded(
        hashedInc: Hashed[GlobalIncrementalSnapshot],
        inc: Signed[GlobalIncrementalSnapshot],
        state: GlobalSnapshotInfo,
        thresholdOrdinal: SnapshotOrdinal
      ): F[Unit] =
        (hashedInc.ordinal > thresholdOrdinal)
          .pure[F]
          .ifM(
            ifTrue = for {
              _ <- lastNGlobalSnapshotStorage.set(hashedInc, state)
              _ <- lastGlobalSnapshotStorage.set(hashedInc, state)
              _ <- HasherSelector[F].withCurrent(implicit h => globalSnapshotStorage.prepend(inc, state))
            } yield (),
            ifFalse = ().pure[F]
          )

      private def discoverHashesChain(targetHash: Hash): F[(Hash, NonEmptyChain[Hash])] = {
        type DiscoveryState = (NonEmptyChain[Hash], Option[SnapshotOrdinal])

        def walkBackwards(state: DiscoveryState): F[Either[DiscoveryState, DiscoveryState]] = {
          val (hashes, _) = state
          val currentHash = hashes.head

          loadIncOrV2OrErr(currentHash).attempt.flatMap {
            case Right(incEither) =>
              val ordinal = incEither.fold(_.ordinal, _.ordinal)
              val parentHash = incEither.fold(_.lastSnapshotHash, _.lastSnapshotHash)
              loadInfo(ordinal).map {
                case Some(_) => (hashes, ordinal.some).asRight[DiscoveryState] // Found info, stop
                case None    => (hashes.prepend(parentHash), ordinal.partialPrevious).asLeft[DiscoveryState] // Continue walking
              }
            case Left(_) => state.asRight[DiscoveryState].pure[F] // Can't load, stop
          }
        }

        def finalizeChain(hashes: NonEmptyChain[Hash], lastOrdinal: Option[SnapshotOrdinal]): F[(Hash, NonEmptyChain[Hash])] = {
          val hashCandidate = hashes.head
          loadIncOrV2OrErr(hashCandidate).attempt
            .map(_.toOption.fold(hashes.tail)(_ => hashes.toChain))
            .flatMap { chain =>
              NonEmptyChain.fromChain(chain) match {
                case Some(incHashes) =>
                  logger.info(s"Chain discovery complete: candidate=$hashCandidate, ordinal=$lastOrdinal") >>
                    (hashCandidate, incHashes).pure[F]
                case None =>
                  RollbackSnapshotNotFound(targetHash).raiseError[F, (Hash, NonEmptyChain[Hash])]
              }
            }
        }

        (NonEmptyChain.one(targetHash), none[SnapshotOrdinal])
          .tailRecM(walkBackwards)
          .flatMap { case (hashes, lastOrdinal) => finalizeChain(hashes, lastOrdinal) }
      }

      private def processRemainingSnapshots(
        chain: List[Hash],
        initialState: GlobalSnapshotInfo,
        initialInc: Signed[GlobalIncrementalSnapshot],
        thresholdOrdinal: SnapshotOrdinal
      ): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])] =
        chain.foldLeftM((initialState, initialInc)) {
          case ((lastCtx, lastInc), hash) =>
            for {
              incEither <- loadIncOrV2OrErr(hash)
              inc = incEither.fold(_.map(_.toGlobalIncrementalSnapshot), identity)
              (hashedInc, updatedState) <- HasherSelector[F].withCurrent { implicit hasher =>
                for {
                  hashed <- inc.toHashed
                  ctx <- contextFns.createContext(lastCtx, lastInc, inc, getGlobalSnapshotByOrdinal)
                } yield (hashed, ctx)
              }
              _ <- updateStoragesIfNeeded(hashedInc, inc, updatedState, thresholdOrdinal)
            } yield (updatedState, inc)
        }

      def loadChain(): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])] =
        for {
          (hashCandidate, incHashes) <- discoverHashesChain(rollbackHash)
          _ <- logger.info(s"Rollback hash candidate: ${hashCandidate.show}")

          firstIncEither <- loadIncOrV2OrErr(incHashes.head)
          firstInfo <- loadFullOrIncOrErr(hashCandidate).flatMap(loadInfoForSnapshot)
          (hashedFirstInc, firstInc) <- validateFirstSnapshot(firstIncEither, firstInfo)

          // For V2 ordinals, skip globalSnapshotStorage.prepend since V2 data already exists on disk
          // with a different hash (V2 format hash vs current format hash). Only initialize memory storages.
          _ <- firstIncEither match {
            case Left(_) =>
              HasherSelector[F].withCurrent { implicit hasher =>
                for {
                  _ <- logger.info(s"First ordinal is V2, skipping disk persist during initialization")
                  _ <- lastNGlobalSnapshotStorage.setInitialFetchingGL0(
                    hashedFirstInc,
                    firstInfo,
                    none,
                    Some((hash, ordinal) => download.fetchSnapshot(hash, ordinal))
                  )
                  _ <- lastGlobalSnapshotStorage.setInitial(hashedFirstInc, firstInfo)
                } yield ()
              }
            case Right(_) =>
              HasherSelector[F].withCurrent { implicit hasher =>
                initializeStorages[F](
                  globalSnapshotStorage,
                  lastNGlobalSnapshotStorage,
                  lastGlobalSnapshotStorage,
                  download,
                  hashedFirstInc,
                  firstInfo
                )
              }
          }

          (finalInfo, finalInc) <- processRemainingSnapshots(
            incHashes.tail.toList,
            firstInfo,
            firstInc,
            hashedFirstInc.ordinal
          )
        } yield (finalInfo, finalInc)
    }
}
