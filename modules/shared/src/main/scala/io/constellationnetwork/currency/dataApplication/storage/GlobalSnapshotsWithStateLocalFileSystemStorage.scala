package io.constellationnetwork.currency.dataApplication.storage

import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.schema.{GlobalSnapshotWithState, SnapshotOrdinal}
import io.constellationnetwork.storage.LocalFileSystemStorage

import eu.timepit.refined.types.numeric.PosLong
import fs2.Stream
import fs2.io.file.Path

final class GlobalSnapshotsWithStateLocalFileSystemStorage[F[_]: Async] private (
  path: Path,
  maxGlobalSnapshotsWithStateStored: PosLong
) extends LocalFileSystemStorage[F, Array[Byte]](path) {
  def read(ordinal: SnapshotOrdinal)(implicit decoder: Array[Byte] => F[GlobalSnapshotWithState]): F[Option[GlobalSnapshotWithState]] =
    readBytes(toOrdinalName(ordinal)).flatMap {
      _.traverse(decoder)
    }

  def exists(ordinal: SnapshotOrdinal): F[Boolean] =
    exists(toOrdinalName(ordinal))

  private def cleanupOldSnapshots(): F[Unit] =
    listStoredOrdinals.flatMap { ordinalsStream =>
      ordinalsStream.compile.toList.flatMap { ordinals =>
        val sortedOrdinals = ordinals.sorted(Ordering[SnapshotOrdinal].reverse) // Latest first
        if (sortedOrdinals.length > maxGlobalSnapshotsWithStateStored.value) {
          val ordinalsToDelete = sortedOrdinals.drop(maxGlobalSnapshotsWithStateStored.value.toInt)
          ordinalsToDelete.traverse_(delete)
        } else {
          Async[F].unit
        }
      }
    }

  def write(ordinal: SnapshotOrdinal, snapshotWithState: GlobalSnapshotWithState)(
    implicit encoder: GlobalSnapshotWithState => F[Array[Byte]]
  ): F[Unit] =
    for {
      encodedData <- encoder(snapshotWithState)
      _ <- write(toOrdinalName(ordinal), encodedData)
      _ <- cleanupOldSnapshots()
    } yield ()

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

  def getLatestOrdinal: F[Option[SnapshotOrdinal]] =
    listStoredOrdinals.flatMap { ordinalsStream =>
      ordinalsStream.compile.toList.map { ordinals =>
        if (ordinals.nonEmpty) Some(ordinals.max) else None
      }
    }

  private def toOrdinalName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString
}

object GlobalSnapshotsWithStateLocalFileSystemStorage {
  def make[F[_]: Async](path: Path, maxGlobalSnapshotsWithStateStored: PosLong): F[GlobalSnapshotsWithStateLocalFileSystemStorage[F]] =
    new GlobalSnapshotsWithStateLocalFileSystemStorage[F](path, maxGlobalSnapshotsWithStateStored).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
