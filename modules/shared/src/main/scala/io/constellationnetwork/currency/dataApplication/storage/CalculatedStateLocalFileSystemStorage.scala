package io.constellationnetwork.currency.dataApplication.storage

import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.storage.LocalFileSystemStorage

import fs2.Stream
import fs2.io.file.Path

final class CalculatedStateLocalFileSystemStorage[F[_]: Async] private (
  path: Path
) extends LocalFileSystemStorage[F, Array[Byte]](path) {
  def read[A](ordinal: SnapshotOrdinal)(implicit decoder: Array[Byte] => F[A]): F[Option[A]] =
    readBytes(toOrdinalName(ordinal)).flatMap {
      _.traverse(decoder)
    }

  def exists(ordinal: SnapshotOrdinal): F[Boolean] =
    exists(toOrdinalName(ordinal))

  def write[A](ordinal: SnapshotOrdinal, state: A)(implicit encoder: A => F[Array[Byte]]): F[Unit] =
    encoder(state).flatMap(write(toOrdinalName(ordinal), _))

  def delete[A](ordinal: SnapshotOrdinal): F[Unit] =
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

object CalculatedStateLocalFileSystemStorage {
  def make[F[_]: Async](path: Path): F[CalculatedStateLocalFileSystemStorage[F]] =
    new CalculatedStateLocalFileSystemStorage[F](path).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
