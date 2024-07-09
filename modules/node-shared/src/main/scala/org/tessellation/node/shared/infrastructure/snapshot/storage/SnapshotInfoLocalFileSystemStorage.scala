package org.tessellation.node.shared.infrastructure.snapshot.storage

import cats.effect.kernel.Async
import cats.syntax.all._

import org.tessellation.currency.schema.currency.{CurrencySnapshotInfo, CurrencySnapshotInfoV1, CurrencySnapshotStateProof}
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.snapshot.{SnapshotInfo, StateProof}
import org.tessellation.schema.{SnapshotOrdinal, _}
import org.tessellation.storage.SerializableLocalFileSystemStorage

import fs2.Stream
import fs2.io.file.Path
import io.circe.{Decoder, Encoder}

abstract class SnapshotInfoLocalFileSystemStorage[
  F[_]: Async: JsonSerializer,
  P <: StateProof,
  S <: SnapshotInfo[P]: Encoder: Decoder
](
  path: Path
) extends SerializableLocalFileSystemStorage[F, S](path) {
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

object GlobalSnapshotInfoLocalFileSystemStorage {
  def make[F[+_]: Async: KryoSerializer: JsonSerializer](
    path: Path
  ): F[SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo]] =
    (new SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo](path) {
      def deserializeFallback(bytes: Array[Byte]): Either[Throwable, GlobalSnapshotInfo] =
        KryoSerializer[F].deserialize[GlobalSnapshotInfoV2](bytes).map(_.toGlobalSnapshotInfo)
    }).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}

object GlobalSnapshotInfoKryoLocalFileSystemStorage {
  def make[F[+_]: Async: KryoSerializer: JsonSerializer](
    path: Path
  ): F[SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfoV2]] =
    (new SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfoV2](path) {
      def deserializeFallback(bytes: Array[Byte]): Either[Throwable, GlobalSnapshotInfoV2] =
        KryoSerializer[F].deserialize[GlobalSnapshotInfoV2](bytes)
    }).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}

object CurrencySnapshotInfoLocalFileSystemStorage {
  def make[F[+_]: Async: KryoSerializer: JsonSerializer](
    path: Path
  ): F[SnapshotInfoLocalFileSystemStorage[F, CurrencySnapshotStateProof, CurrencySnapshotInfo]] =
    (new SnapshotInfoLocalFileSystemStorage[F, CurrencySnapshotStateProof, CurrencySnapshotInfo](path) {
      def deserializeFallback(bytes: Array[Byte]): Either[Throwable, CurrencySnapshotInfo] =
        KryoSerializer[F].deserialize[CurrencySnapshotInfoV1](bytes).map(_.toCurrencySnapshotInfo)
    }).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
