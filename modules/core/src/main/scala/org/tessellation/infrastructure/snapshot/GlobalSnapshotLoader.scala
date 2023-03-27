package org.tessellation.infrastructure.snapshot

import cats.effect.kernel.Async
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshot}
import org.tessellation.sdk.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import fs2.io.file.Path

trait GlobalSnapshotLoader[F[_]] {

  def readGlobalSnapshot(hash: Hash): F[Option[Signed[GlobalSnapshot]]]
  def readGlobalIncrementalSnapshot(hash: Hash): F[Option[Signed[GlobalIncrementalSnapshot]]]
}

object GlobalSnapshotLoader {

  def make[F[_]: Async: KryoSerializer](
    incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    path: Path
  ): F[GlobalSnapshotLoader[F]] =
    SnapshotLocalFileSystemStorage.make[F, GlobalSnapshot](path).map(make(incrementalGlobalSnapshotLocalFileSystemStorage, _))

  def make[F[_]: Async](
    incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    globalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot]
  ): GlobalSnapshotLoader[F] = new GlobalSnapshotLoader[F] {

    def readGlobalSnapshot(hash: Hash): F[Option[Signed[GlobalSnapshot]]] =
      globalSnapshotLocalFileSystemStorage.read(hash)

    def readGlobalIncrementalSnapshot(hash: Hash): F[Option[Signed[GlobalIncrementalSnapshot]]] =
      incrementalGlobalSnapshotLocalFileSystemStorage.read(hash)

  }

}
