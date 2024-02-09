package org.tessellation.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.effect.syntax.all._
import cats.syntax.all._

import org.tessellation.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import org.tessellation.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import org.tessellation.merkletree.StateProofValidator
import org.tessellation.node.shared.infrastructure.snapshot.storage.{SnapshotInfoLocalFileSystemStorage, SnapshotLocalFileSystemStorage}
import org.tessellation.schema._
import org.tessellation.security._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

object SnapshotDownloadStorage {
  def make[F[_]: Async: Hasher](
    tmpStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    persistedStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    fullGlobalSnapshotStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot],
    snapshotInfoStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
    hashSelect: HashSelect
  ): SnapshotDownloadStorage[F] =
    new SnapshotDownloadStorage[F] {

      val cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make

      val maxParallelFileOperations = 4

      def readPersisted(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = persistedStorage.read(ordinal)

      def readTmp(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = tmpStorage.read(ordinal)

      def writeTmp(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] =
        tmpStorage.exists(snapshot.ordinal).flatMap(tmpStorage.delete(snapshot.ordinal).whenA) >>
          tmpStorage.writeUnderOrdinal(snapshot)

      def writePersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] = persistedStorage.write(snapshot)

      def deletePersisted(ordinal: SnapshotOrdinal): F[Unit] = persistedStorage.delete(ordinal)

      def isPersisted(hash: Hash): F[Boolean] = persistedStorage.exists(hash)

      def hasCorrectSnapshotInfo(ordinal: SnapshotOrdinal, proof: GlobalSnapshotStateProof): F[Boolean] =
        snapshotInfoStorage.read(ordinal).flatMap {
          case Some(info) => info.stateProof(ordinal, hashSelect).map(_ === proof)
          case _          => false.pure[F]
        }

      def getHighestSnapshotInfoOrdinal(lte: SnapshotOrdinal): F[Option[SnapshotOrdinal]] =
        snapshotInfoStorage.listStoredOrdinals
          .flatMap(_.filter(_ <= lte).compile.toList)
          .map(_.maximumOption)

      def readCombined(ordinal: SnapshotOrdinal): F[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
        (readPersisted(ordinal), snapshotInfoStorage.read(ordinal)).tupled.map(_.tupled).flatMap {
          case Some((snapshot, info)) =>
            StateProofValidator
              .validate(snapshot, info, hashSelect)
              .map(_.isValid)
              .ifM(
                (snapshot, info).some.pure[F],
                new Exception("Persisted snapshot info does not match the persisted snapshot")
                  .raiseError[F, Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]
              )
          case _ => none.pure[F]
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
        persistedStorage.getPath(snapshot).flatMap(tmpStorage.moveByOrdinal(snapshot, _) >> persistedStorage.link(snapshot))

      def readGenesis(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]] = fullGlobalSnapshotStorage.read(ordinal)

      def writeGenesis(genesis: Signed[GlobalSnapshot]): F[Unit] = fullGlobalSnapshotStorage.write(genesis)

      def cleanupAbove(ordinal: SnapshotOrdinal): F[Unit] =
        snapshotInfoStorage.deleteAbove(ordinal) >>
          persistedStorage
            .findFiles(_.name.toLongOption.exists(_ > ordinal.value.value))
            .map {
              _.map(_.name.toLongOption.flatMap(SnapshotOrdinal(_))).collect { case Some(a) => a }
            }
            .flatMap {
              _.compile.toList.flatMap {
                _.parTraverseN(maxParallelFileOperations) { ordinal =>
                  readPersisted(ordinal).flatMap {
                    case Some(snapshot) =>
                      snapshot.toHashed.flatMap(s => movePersistedToTmp(s.hash, s.ordinal))
                    case None => Async[F].unit
                  }
                }.void
              }
            }
    }
}
