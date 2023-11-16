package org.tessellation.sdk.infrastructure.snapshot.storage

import cats.effect.kernel.Async
import cats.syntax.all._

import scala.reflect.ClassTag

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.snapshot.{SnapshotInfo, StateProof}
import org.tessellation.storage.KryoLocalFileSystemStorage

import fs2.Stream
import fs2.io.file.Path

final class SnapshotInfoLocalFileSystemStorage[F[_]: Async: KryoSerializer, P <: StateProof, S <: SnapshotInfo[P]: ClassTag] private (
  path: Path
) extends KryoLocalFileSystemStorage[F, S](path) {
  def write(ordinal: SnapshotOrdinal, snapshotInfo: S): F[Unit] = {
    val ordinalName = toOrdinalName(ordinal)

    write(ordinalName, snapshotInfo)
  }

  def read(ordinal: SnapshotOrdinal): F[Option[S]] =
    read(toOrdinalName(ordinal))

  def exists(ordinal: SnapshotOrdinal): F[Boolean] =
    exists(toOrdinalName(ordinal))

  def delete(ordinal: SnapshotOrdinal): F[Unit] =
    delete(toOrdinalName(ordinal))

  def listStoredOrdinals: F[Stream[F, SnapshotOrdinal]] =
    listFiles.map {
      _.map(_.name)
        .map(_.toLongOption)
        .map(_.flatMap(SnapshotOrdinal(_)))
        .collect {
          case Some(a) => a
        }
    }

  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
    listStoredOrdinals.flatMap {
      _.filter(_ > ordinal)
        .evalMap(delete)
        .compile
        .drain
    }

  private def toOrdinalName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString
}

object SnapshotInfoLocalFileSystemStorage {
  def make[F[_]: Async: KryoSerializer, P <: StateProof, S <: SnapshotInfo[P]: ClassTag](
    path: Path
  ): F[SnapshotInfoLocalFileSystemStorage[F, P, S]] =
    new SnapshotInfoLocalFileSystemStorage[F, P, S](path).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
