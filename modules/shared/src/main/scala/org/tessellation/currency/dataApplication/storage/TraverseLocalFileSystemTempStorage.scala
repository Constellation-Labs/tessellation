package org.tessellation.currency.dataApplication.storage

import cats.effect.{Async, Resource}
import cats.syntax.all._

import scala.util.control.NoStackTrace

import org.tessellation.currency.dataApplication.storage.TraverseLocalFileSystemTempStorage.{
  SnapshotAlreadyExistsInTempStorage,
  SnapshotNotFoundInTempStorage
}
import org.tessellation.currency.schema.currency.CurrencyIncrementalSnapshot
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.storage.SerializableLocalFileSystemStorage

import fs2.Stream
import fs2.io.file.{Files, Path}

final class TraverseLocalFileSystemTempStorage[F[_]: Async: KryoSerializer: JsonSerializer] private (path: Path)
    extends SerializableLocalFileSystemStorage[F, CurrencyIncrementalSnapshot](path) {

  def read(
    ordinal: SnapshotOrdinal
  ): F[CurrencyIncrementalSnapshot] =
    read(toOrdinalName(ordinal)).flatMap {
      case Some(snapshot) => snapshot.pure[F]
      case None           => SnapshotNotFoundInTempStorage(ordinal).raiseError[F, CurrencyIncrementalSnapshot]
    }

  def write(ordinal: SnapshotOrdinal, snapshot: CurrencyIncrementalSnapshot): F[Unit] = {
    val name = toOrdinalName(ordinal)

    exists(name)
      .flatMap(SnapshotAlreadyExistsInTempStorage(ordinal).raiseError[F, Unit].whenA)
      .flatMap { _ =>
        write(name, snapshot)
      }
  }

  def listStoredOrdinals: F[Stream[F, SnapshotOrdinal]] =
    listFiles.map {
      _.map(_.name)
        .map(_.toLongOption)
        .map(_.flatMap(SnapshotOrdinal(_)))
        .collect {
          case Some(a) => a
        }
    }

  private def toOrdinalName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString
}

object TraverseLocalFileSystemTempStorage {

  trait TempStorageError extends NoStackTrace
  case class SnapshotAlreadyExistsInTempStorage(ordinal: SnapshotOrdinal) extends TempStorageError
  case class SnapshotNotFoundInTempStorage(ordinal: SnapshotOrdinal) extends TempStorageError

  private def make[F[_]: Async]: Resource[F, Path] =
    Files.forAsync[F].tempDirectory

  def forAsync[F[_]: Async: KryoSerializer: JsonSerializer]: Resource[F, TraverseLocalFileSystemTempStorage[F]] = make[F].map { path =>
    new TraverseLocalFileSystemTempStorage[F](path)
  }
}
