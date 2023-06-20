package org.tessellation.infrastructure.snapshot

import cats.FlatMap
import cats.syntax.flatMap._

import org.tessellation.domain.snapshot.storages.SnapshotDownloadStorage
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.sdk.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

object SnapshotDownloadStorage {
  def make[F[_]: FlatMap](
    tmpStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    persistedStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    fullGlobalSnapshotStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot]
  ): SnapshotDownloadStorage[F] =
    new SnapshotDownloadStorage[F] {

      def readPersisted(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = persistedStorage.read(ordinal)
      def readTmp(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = tmpStorage.read(ordinal)

      def writeTmp(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] = tmpStorage.writeUnderOrdinal(snapshot)
      def writePersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] = persistedStorage.write(snapshot)

      def deletePersisted(ordinal: SnapshotOrdinal): F[Unit] = persistedStorage.delete(ordinal)

      def isPersisted(hash: Hash): F[Boolean] = persistedStorage.exists(hash)

      def movePersistedToTmp(hash: Hash, ordinal: SnapshotOrdinal): F[Unit] =
        tmpStorage.getPath(hash).flatMap(persistedStorage.move(hash, _) >> persistedStorage.delete(ordinal))

      def moveTmpToPersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] =
        persistedStorage.getPath(snapshot).flatMap(tmpStorage.moveByOrdinal(snapshot, _) >> persistedStorage.link(snapshot))

      def readGenesis(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]] = fullGlobalSnapshotStorage.read(ordinal)
    }
}
