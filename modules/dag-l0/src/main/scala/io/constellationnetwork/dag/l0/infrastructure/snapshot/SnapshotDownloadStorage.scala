package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
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

import org.typelevel.log4cats.slf4j.Slf4jLogger

object SnapshotDownloadStorage {
  def make[F[_]: Async: Parallel: HasherSelector: KryoSerializer: JsonSerializer](
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

      private val validator = StateProofValidator.forGlobal(mptStore)
      private val builder = GlobalSnapshotInfo.stateProofBuilderWithStore(mptStore)

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
              _ <- info match {
                case Left(value) => ().pure[F]
                case Right(value) =>
                  value.allStateEntries[F].flatMap { kvPairs =>
                    mptStore.syncFull(kvPairs, ordinal)
                  }
              }
              result <- (info match {
                case Left(infoV2) =>
                  infoV2.stateProof(ordinal).flatMap(proof => StateProofValidator.validateProof(snapshot, proof).map(_.isValid))
                case Right(gsi) =>
                  validator.validate(snapshot, gsi).map(_.isValid)
              }).ifM(
                (snapshot.signed, info.leftMap(_.toGlobalSnapshotInfo).fold(identity, identity)).some.pure[F],
                new Exception("Persisted snapshot info does not match the persisted snapshot")
                  .raiseError[F, Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]
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
            .handleErrorWith(err =>
              logger.error(err)(s"Error while deleting snapshot_info files above ${ordinal.show}") >>
                Async[F].raiseError(err)
            )
          _ <- logger.info(s"Successfully deleted snapshot_info files above ordinal ${ordinal.show}")
        } yield ()

        val cleanupAboveOrdinal = persistedStorage.cleanupAboveOrdinal(ordinal, movePersistedToTmp)

        val verify = for {
          remainingFiles <- persistedStorage
            .findAbove(ordinal)
            .compile
            .count

          _ <-
            if (remainingFiles > 0) {
              throw new RuntimeException(s"Cleanup incomplete: $remainingFiles files still remain above ordinal ${ordinal.show}")
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
