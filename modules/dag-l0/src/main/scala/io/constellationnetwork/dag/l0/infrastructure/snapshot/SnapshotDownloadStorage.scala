package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.snapshot.programs.SnapshotFailure
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{
  CombinedSnapshotCheckpointFileSystemStorage,
  SnapshotInfoLocalFileSystemStorage,
  SnapshotLocalFileSystemStorage
}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.validator.StateProofValidator

import eu.timepit.refined.auto._
import io.circe.Json
import org.typelevel.log4cats.slf4j.Slf4jLogger

object SnapshotDownloadStorage {
  def make[F[_]: Async: Parallel: HasherSelector: KryoSerializer: JsonSerializer: Metrics](
    tmpStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    persistedStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    fullGlobalSnapshotStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot],
    snapshotInfoStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
    snapshotInfoKryoStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfoV2],
    combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[
      F,
      GlobalIncrementalSnapshot,
      GlobalSnapshotInfo
    ],
    hashSelect: HashSelect,
    mptStore: MptStore[F, GlobalStateKey]
  )(
    implicit globalStateProofSelector: GlobalStateProofSelector
  ): SnapshotDownloadStorage[F] =
    new SnapshotDownloadStorage[F] {

      val logger = Slf4jLogger.getLogger[F]

      val cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make

      private val validator = StateProofValidator.forGlobal(Some(mptStore.underlying))
      private val builder = GlobalSnapshotInfo.stateProofBuilder(Some(mptStore.underlying))

      def readPersisted(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = persistedStorage.read(ordinal)

      def readTmp(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = tmpStorage.read(ordinal)

      def writeTmp(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] =
        tmpStorage.exists(snapshot.ordinal).flatMap(tmpStorage.delete(snapshot.ordinal).whenA) >>
          tmpStorage.writeUnderOrdinal(snapshot)

      def writePersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] = HasherSelector[F].withCurrent { implicit hasher =>
        persistedStorage.write(snapshot)
      }

      def deletePersisted(ordinal: SnapshotOrdinal): F[Unit] = persistedStorage.delete(ordinal)

      def isPersisted(hash: Hash): F[Boolean] = persistedStorage.exists(hash)

      def hasCorrectSnapshotInfo(
        ordinal: SnapshotOrdinal,
        proof: GlobalSnapshotStateProof
      )(implicit hasher: Hasher[F]): F[Boolean] =
        (hashSelect.select(ordinal) match {
          case JsonHash => snapshotInfoStorage.read(ordinal).flatMap(_.traverse(builder.buildProof(_, ordinal)))
          case KryoHash =>
            snapshotInfoKryoStorage.read(ordinal).flatMap(_.traverse(i => builder.buildProof(i.toGlobalSnapshotInfo, ordinal)))
        }).map {
          case Some(calculatedProof) => calculatedProof === proof
          case _                     => false
        }

      def getHighestSnapshotInfoOrdinal(lte: SnapshotOrdinal): F[Option[SnapshotOrdinal]] =
        snapshotInfoStorage.listStoredOrdinals
          .flatMap(_.filter(_ <= lte).compile.toList)
          .map(_.maximumOption)

      def readCombined(
        ordinal: SnapshotOrdinal
      )(
        implicit hasher: Hasher[F],
        stateProofSelector: StateProofSelector
      ): F[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = {
        val maybeInfo = hashSelect.select(ordinal) match {
          case JsonHash => snapshotInfoStorage.read(ordinal).map(_.map(_.asRight[GlobalSnapshotInfoV2]))
          case KryoHash => snapshotInfoKryoStorage.read(ordinal).map(_.map(_.asLeft[GlobalSnapshotInfo]))
        }

        (readPersisted(ordinal).flatMap(_.traverse(_.toHashed)), maybeInfo).tupled.map(_.tupled).flatMap {
          case Some((snapshot, info)) =>
            for {
              // Use syncFullIfNeeded for atomic sync - avoids redundant syncs if already at this ordinal
              _ <- info match {
                case Left(value) => ().pure[F]
                case Right(value) =>
                  mptStore.syncFullIfNeeded[Json](value.allStateEntries[F], ordinal)
              }
              result <- (info match {
                case Left(infoV2) =>
                  infoV2.stateProof(ordinal).flatMap(proof => StateProofValidator.validateProof(snapshot, proof).map(_.isValid))
                case Right(gsi) =>
                  validator.validate(snapshot, gsi).map(_.isValid)
              }).ifM(
                (snapshot.signed, info.leftMap(_.toGlobalSnapshotInfo).fold(identity, identity)).some.pure[F],
                // Self-heal: persisted (snapshot, info) at this ordinal don't agree on the state proof.
                // Causes include MPT drift, stale rollback artifacts, or cross-version persistence. The
                // pair is unrecoverable in place -- delete both files and return None so the caller falls
                // back to genesis re-download. Safe because canonical state is always re-fetchable from
                // source nodes; the alternative (raise + cycle WFD<->DLI forever) wedges the peer.
                logger.warn(
                  s"[readCombined] persisted (snapshot, info) at ordinal=${ordinal.show} state-proof mismatch -- " +
                    s"discarding both files for fresh re-download"
                ) >>
                  Metrics[F]
                    .incrementCounter("dag_download_persisted_state_self_heal_total", Seq.empty) >>
                  snapshotInfoStorage.delete(ordinal).handleErrorWith {
                    case _: java.nio.file.NoSuchFileException => Async[F].unit
                    case err =>
                      logger.warn(err)(s"[readCombined] failed to delete persisted info (json) at ordinal=${ordinal.show}")
                  } >>
                  snapshotInfoKryoStorage.delete(ordinal).handleErrorWith {
                    case _: java.nio.file.NoSuchFileException => Async[F].unit
                    case err =>
                      logger.warn(err)(s"[readCombined] failed to delete persisted info (kryo) at ordinal=${ordinal.show}")
                  } >>
                  persistedStorage.delete(ordinal).handleErrorWith {
                    case _: java.nio.file.NoSuchFileException => Async[F].unit
                    case err =>
                      logger.warn(err)(s"[readCombined] failed to delete persisted snapshot at ordinal=${ordinal.show}")
                  } >>
                  none[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)].pure[F]
              )
            } yield result
          case _ => none[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)].pure[F]
        }
      }

      def persistSnapshotInfoWithCutoff(ordinal: SnapshotOrdinal, info: GlobalSnapshotInfo): F[Unit] =
        snapshotInfoStorage.write(ordinal, info) >> {
          val toKeep = cutoffLogic.cutoff(SnapshotOrdinal.MinValue, ordinal)

          snapshotInfoStorage.listStoredOrdinals.flatMap {
            _.compile.toList
              .map(_.toSet.diff(toKeep).toList)
              .flatMap(_.traverse_(snapshotInfoStorage.delete))
          }
        }

      def movePersistedToTmp(hash: Hash, ordinal: SnapshotOrdinal): F[Unit] =
        tmpStorage.getPath(hash).flatMap(persistedStorage.move(hash, _) >> persistedStorage.delete(ordinal))

      def moveTmpToPersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] =
        HasherSelector[F].withCurrent { implicit hasher =>
          persistedStorage.getPath(snapshot).flatMap(tmpStorage.moveByOrdinal(snapshot, _) >> persistedStorage.link(snapshot))
        }

      def readGenesis(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]] = fullGlobalSnapshotStorage.read(ordinal)

      def writeGenesis(genesis: Signed[GlobalSnapshot]): F[Unit] = HasherSelector[F].withCurrent { implicit hasher =>
        fullGlobalSnapshotStorage.write(genesis)
      }

      def cleanupAbove(ordinal: SnapshotOrdinal): F[Unit] = {
        val deleteSnapshotInfo = for {
          _ <- logger.info(s"Starting cleanup above ordinal ${ordinal.show}")
          _ <- snapshotInfoStorage
            .deleteAbove(ordinal)
            .handleErrorWith {
              case _: java.nio.file.NoSuchFileException =>
                // Files already deleted - not an error
                logger.debug(s"Snapshot_info files above ${ordinal.show} already deleted or do not exist")
              case err =>
                logger.error(err)(s"Error while deleting snapshot_info files above ${ordinal.show}") >>
                  Async[F].raiseError(err)
            }
          _ <- logger.debug(s"Completed snapshot_info cleanup above ordinal ${ordinal.show}")
        } yield ()

        val cleanupAboveOrdinal = persistedStorage.cleanupAboveOrdinal(ordinal, movePersistedToTmp)

        // Fallback direct-deletion when the standard cleanupAboveOrdinal path leaves files behind.
        // Triggered when persistedStorage.cleanupAboveOrdinal's movePersistedToTmp fails (e.g. tmp
        // destination directories don't exist for orphaned hashes -- the failure is swallowed by
        // processFileChunk as NoSuchFileException, so the ordinal hardlinks survive on disk).
        // findAbove enumerates only the ordinal/ subtree, so deleting each ordinal hardlink is
        // sufficient to satisfy the verify check. Any orphaned hash/ files are left in place; they
        // are unreachable from normal lookup paths since the ordinal hardlink is gone, and an
        // operator can collect them later. Bounded: a max-iteration cap prevents infinite loops if
        // the deletion itself fails permission-wise.
        val maxFallbackIterations = 3

        def fallbackDirectDelete: F[Long] =
          persistedStorage
            .findAbove(ordinal)
            .evalMap { file =>
              file.name.toLongOption.flatMap(SnapshotOrdinal(_)) match {
                case Some(ord) =>
                  persistedStorage.delete(ord).handleErrorWith {
                    case _: java.nio.file.NoSuchFileException => Async[F].unit
                    case err =>
                      logger.warn(err)(s"[cleanupAbove] fallback delete failed for ordinal=${ord.show}")
                  }
                case None =>
                  logger.debug(s"[cleanupAbove] skipping non-ordinal file in fallback: ${file.pathAsString}")
              }
            }
            .compile
            .drain >>
            persistedStorage.findAbove(ordinal).compile.count

        def fallbackLoop(iteration: Int): F[Long] =
          fallbackDirectDelete.flatMap { remaining =>
            if (remaining === 0L || iteration >= maxFallbackIterations) remaining.pure[F]
            else
              logger.warn(
                s"[cleanupAbove] fallback iteration=$iteration still has $remaining files; retrying (max=$maxFallbackIterations)"
              ) >> fallbackLoop(iteration + 1)
          }

        val verify = for {
          remainingFiles <- persistedStorage
            .findAbove(ordinal)
            .compile
            .count

          // Always update the gauge so /metrics on a remote community peer can answer
          // "how many files refused to clean up?" without log access. 0 on success.
          _ <- Metrics[F].updateGauge("dag_download_cleanup_remaining_files", remainingFiles.toDouble)

          _ <-
            if (remainingFiles > 0) {
              logger.warn(
                s"[cleanupAbove] $remainingFiles files remain above ordinal=${ordinal.show} after standard cleanup; " +
                  s"attempting fallback direct deletion"
              ) >>
                Metrics[F].incrementCounter("dag_download_cleanup_fallback_total", Seq.empty) >>
                fallbackLoop(iteration = 1).flatMap { stillRemaining =>
                  Metrics[F].updateGauge("dag_download_cleanup_remaining_files", stillRemaining.toDouble) >> {
                    if (stillRemaining > 0L)
                      Async[F].raiseError[Unit](SnapshotFailure.CleanupIncomplete(stillRemaining, ordinal))
                    else
                      logger.info(
                        s"[cleanupAbove] fallback succeeded: removed $remainingFiles orphan ordinal hardlinks above ${ordinal.show}"
                      )
                  }
                }
            } else {
              logger.info(s"Cleanup successful: No files remain above ordinal ${ordinal.show}")
            }
        } yield ()

        deleteSnapshotInfo >>
          cleanupAboveOrdinal >>
          verify >>
          combinedSnapshotCheckpointFileSystemStorage.deleteAbove(ordinal) >>
          mptStore.deleteAbove(ordinal)
      }
    }
}
