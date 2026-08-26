package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.currency.schema.currency.{CurrencySnapshotInfo, CurrencySnapshotInfoV1, CurrencySnapshotStateProof}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.infrastructure.storage.CrashSafeAtomicFileWriter
import io.constellationnetwork.schema.snapshot.{SnapshotInfo, StateProof}
import io.constellationnetwork.schema.{SnapshotOrdinal, _}
import io.constellationnetwork.storage.SerializableLocalFileSystemStorage

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

  /** Atomic context replacement used only after the caller has validated an exact recovery authority.
    */
  def replaceForRecovery(ordinal: SnapshotOrdinal, snapshotInfo: S): F[Unit] = {
    val fileName = toOrdinalName(ordinal)

    for {
      bytes <- JsonSerializer[F].serialize(snapshotInfo)
      writer <- CrashSafeAtomicFileWriter.make[F](path)
      _ <- writer.write(fileName, bytes)
      stored <- readBytes(fileName).flatMap(
        _.liftTo[F](new IllegalStateException(s"Recovery snapshot-info missing after replace ordinal=$ordinal"))
      )
      _ <- Async[F].raiseUnless(java.util.Arrays.equals(bytes, stored))(
        new IllegalStateException(s"Recovery snapshot-info exact disk readback failed ordinal=$ordinal")
      )
    } yield ()
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
        KryoSerializer[F]
          .deserialize[GlobalSnapshotInfoV2](bytes)
          .map(v2 => GlobalSnapshotInfoV2.fromGlobalSnapshotInfo(v2.toGlobalSnapshotInfo))
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
