package io.constellationnetwork.currency.dataApplication.storage

import cats.effect.{Async, Resource}
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.dataApplication.storage.TraverseLocalFileSystemTempStorage.{
  SnapshotAlreadyExistsInTempStorage,
  SnapshotNotFoundInTempStorage
}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencyIncrementalSnapshotV1}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.storage.SerializableLocalFileSystemStorage

import fs2.Stream
import fs2.io.file.{Files, Path}

final class TraverseLocalFileSystemTempStorage[F[_]: Async: KryoSerializer: JsonSerializer] private (path: Path)
    extends SerializableLocalFileSystemStorage[F, Signed[CurrencyIncrementalSnapshot]](path) {

  def deserializeFallback(bytes: Array[Byte]): Either[Throwable, Signed[CurrencyIncrementalSnapshot]] =
    KryoSerializer[F]
      .deserialize[Signed[CurrencyIncrementalSnapshotV1]](bytes)
      .map(signed => Signed(signed.value.toCurrencyIncrementalSnapshot, signed.proofs))

  def read(
    ordinal: SnapshotOrdinal
  ): F[Signed[CurrencyIncrementalSnapshot]] =
    read(toOrdinalName(ordinal)).flatMap {
      case Some(snapshot) => snapshot.pure[F]
      case None           => SnapshotNotFoundInTempStorage(ordinal).raiseError[F, Signed[CurrencyIncrementalSnapshot]]
    }

  def write(ordinal: SnapshotOrdinal, snapshot: Signed[CurrencyIncrementalSnapshot]): F[Unit] = {
    val name = toOrdinalName(ordinal)

    exists(name).flatMap {
      case true  => Async[F].unit
      case false => write(name, snapshot)
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
