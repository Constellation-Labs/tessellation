package io.constellationnetwork.security.mpt.storages

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.storage.SerializableLocalFileSystemStorage

import fs2.Stream
import fs2.io.file.Path
import io.circe.{Decoder, Encoder, Json}

object MerklePatriciaHexMap {
  type HexMap = Map[Hex, Json]

  implicit val hexKeyEncoder: io.circe.KeyEncoder[Hex] = (key: Hex) => key.value
  implicit val hexKeyDecoder: io.circe.KeyDecoder[Hex] = (key: String) => Some(Hex(key))

  implicit val hexMapEncoder: Encoder[HexMap] = Encoder.encodeMap[Hex, Json]
  implicit val hexMapDecoder: Decoder[HexMap] = Decoder.decodeMap[Hex, Json]
}

class MerklePatriciaHexMapLocalFileSystemStorage[F[_]: Async: JsonSerializer](
  path: Path,
  cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make
) extends SerializableLocalFileSystemStorage[F, MerklePatriciaHexMap.HexMap](path) {

  import MerklePatriciaHexMap._

  override def deserializeFallback(bytes: Array[Byte]): Either[Throwable, HexMap] =
    io.circe.parser.decode[HexMap](new String(bytes, "UTF-8")).leftMap(e => new RuntimeException(e.getMessage))

  private def toOrdinalName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString

  def write(ordinal: SnapshotOrdinal, hexMap: HexMap): F[Unit] =
    write(toOrdinalName(ordinal), hexMap)

  def read(ordinal: SnapshotOrdinal): F[Option[HexMap]] =
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
        .collect { case Some(ordinal) => ordinal }
    }

  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
    listStoredOrdinals.flatMap {
      _.filter(_ > ordinal)
        .evalMap(delete)
        .compile
        .drain
    }

  def applyCutoff(currentOrdinal: SnapshotOrdinal): F[Unit] = {
    val toKeep = cutoffLogic.cutoff(SnapshotOrdinal.MinValue, currentOrdinal)

    listStoredOrdinals.flatMap {
      _.compile.toList
        .map(_.toSet.diff(toKeep).toList)
        .flatMap(_.traverse_(delete))
    }
  }
}

object MerklePatriciaHexMapLocalFileSystemStorage {

  def make[F[_]: Async: JsonSerializer](path: Path): F[MerklePatriciaHexMapLocalFileSystemStorage[F]] =
    (new MerklePatriciaHexMapLocalFileSystemStorage[F](path)).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }

  def make[F[_]: Async: JsonSerializer](
    path: Path,
    cutoffLogic: OrdinalCutoff
  ): F[MerklePatriciaHexMapLocalFileSystemStorage[F]] =
    (new MerklePatriciaHexMapLocalFileSystemStorage[F](path, cutoffLogic)).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
