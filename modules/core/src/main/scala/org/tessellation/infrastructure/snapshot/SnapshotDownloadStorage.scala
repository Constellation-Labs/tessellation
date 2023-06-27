package org.tessellation.infrastructure.snapshot

import cats.effect.Async
import cats.effect.syntax.concurrent._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.domain.snapshot.storages.SnapshotDownloadStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.sdk.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong

object SnapshotDownloadStorage {
  def make[F[_]: Async: KryoSerializer](
    tmpStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    persistedStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    fullGlobalSnapshotStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot]
  ): SnapshotDownloadStorage[F] =
    new SnapshotDownloadStorage[F] {

      val maxParallelFileOperations = 4

      def readPersisted(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = persistedStorage.read(ordinal)

      def readTmp(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = tmpStorage.read(ordinal)

      def writeTmp(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] =
        tmpStorage.exists(snapshot.ordinal).flatMap(tmpStorage.delete(snapshot.ordinal).whenA) >>
          tmpStorage.writeUnderOrdinal(snapshot)

      def writePersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] = persistedStorage.write(snapshot)

      def deletePersisted(ordinal: SnapshotOrdinal): F[Unit] = persistedStorage.delete(ordinal)

      def isPersisted(hash: Hash): F[Boolean] = persistedStorage.exists(hash)

      def movePersistedToTmp(hash: Hash, ordinal: SnapshotOrdinal): F[Unit] =
        tmpStorage.getPath(hash).flatMap(persistedStorage.move(hash, _) >> persistedStorage.delete(ordinal))

      def moveTmpToPersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] =
        persistedStorage.getPath(snapshot).flatMap(tmpStorage.moveByOrdinal(snapshot, _) >> persistedStorage.link(snapshot))

      def readGenesis(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]] = fullGlobalSnapshotStorage.read(ordinal)

      def backupPersistedAbove(ordinal: SnapshotOrdinal): F[Unit] =
        persistedStorage
          .findFiles(_.name.toLongOption.exists(_ > ordinal.value.value))
          .map {
            _.map(_.name.toLongOption.flatMap(NonNegLong.from(_).toOption)).collect { case Some(o) => SnapshotOrdinal(o) }
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
