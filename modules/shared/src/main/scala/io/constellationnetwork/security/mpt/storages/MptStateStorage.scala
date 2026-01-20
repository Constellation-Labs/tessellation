package io.constellationnetwork.security.mpt.storages

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.storage.SerializableLocalFileSystemStorage

import fs2.io.file.Path
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Storage for MPT state only. Trie is rebuilt on load to save memory and disk space. */
class MptStateStorage[F[_]: Async: JsonSerializer](
  path: Path,
  cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make
) extends SerializableLocalFileSystemStorage[F, Map[Hex, Array[Byte]]](path) {

  private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  implicit val stateEncoder: Encoder[Map[Hex, Array[Byte]]] =
    Encoder.instance { map =>
      Json.obj(map.toList.map { case (hex, bytes) => hex.value -> bytes.asJson }: _*)
    }

  implicit val stateDecoder: Decoder[Map[Hex, Array[Byte]]] =
    Decoder.instance { cursor =>
      cursor.as[Map[String, Array[Byte]]].map(_.map { case (k, v) => Hex(k) -> v })
    }

  override def deserializeFallback(bytes: Array[Byte]): Either[Throwable, Map[Hex, Array[Byte]]] =
    io.circe.parser
      .decode[Map[Hex, Array[Byte]]](new String(bytes, "UTF-8"))
      .leftMap(e => new RuntimeException(s"Failed to deserialize state: ${e.getMessage}"))

  private def toName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString

  def writeState(ordinal: SnapshotOrdinal, state: Map[Hex, Array[Byte]]): F[Unit] =
    write(toName(ordinal), state)

  def readState(ordinal: SnapshotOrdinal): F[Option[Map[Hex, Array[Byte]]]] =
    read(toName(ordinal))

  def exists(ordinal: SnapshotOrdinal): F[Boolean] =
    exists(toName(ordinal))

  def delete(ordinal: SnapshotOrdinal): F[Unit] =
    delete(toName(ordinal))

  def listStoredOrdinals: F[List[SnapshotOrdinal]] =
    listFiles.flatMap(
      _.map(_.name.toLongOption.flatMap(SnapshotOrdinal(_))).collect { case Some(o) => o }.compile.toList
    )

  def findLatestOrdinal: F[Option[SnapshotOrdinal]] =
    listStoredOrdinals.map(_.maxOption)

  def readLatest: F[Option[(SnapshotOrdinal, Map[Hex, Array[Byte]])]] =
    findLatestOrdinal.flatMap {
      case Some(ordinal) => readState(ordinal).map(_.map(ordinal -> _))
      case None          => none.pure[F]
    }

  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
    for {
      ordinals <- listStoredOrdinals
      toDelete = ordinals.filter(_ > ordinal)
      _ <- toDelete.traverse_(delete)
      _ <- logger.info(s"[MptStateStorage] Deleted ${toDelete.size} files above ordinal $ordinal")
    } yield ()

  def applyCutoff(currentOrdinal: SnapshotOrdinal): F[Unit] =
    for {
      stored <- listStoredOrdinals
      toKeep = cutoffLogic.cutoff(SnapshotOrdinal.MinValue, currentOrdinal)
      toDelete = stored.toSet.diff(toKeep).toList
      _ <- toDelete.traverse_(delete)
      _ <- if (toDelete.nonEmpty) logger.debug(s"[MptStateStorage] Cutoff removed ${toDelete.size} old files") else Async[F].unit
    } yield ()
}

object MptStateStorage {

  def make[F[_]: Async: JsonSerializer](path: Path): F[MptStateStorage[F]] =
    new MptStateStorage[F](path).pure[F].flatTap(_.createDirectoryIfNotExists().rethrowT)

  def make[F[_]: Async: JsonSerializer](path: Path, cutoffLogic: OrdinalCutoff): F[MptStateStorage[F]] =
    new MptStateStorage[F](path, cutoffLogic).pure[F].flatTap(_.createDirectoryIfNotExists().rethrowT)
}
