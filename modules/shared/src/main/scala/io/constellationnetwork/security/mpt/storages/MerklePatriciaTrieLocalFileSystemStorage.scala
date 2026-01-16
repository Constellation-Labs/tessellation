package io.constellationnetwork.security.mpt.storages

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.MerklePatriciaTrie
import io.constellationnetwork.storage.SerializableLocalFileSystemStorage

import fs2.Stream
import fs2.io.file.Path
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object MerklePatriciaTrieStorage {

  /** Container for persisting both the trie and the state map together.
    */
  case class PersistedTrieData(
    trie: MerklePatriciaTrie,
    state: Map[Hex, Json]
  )

  implicit val persistedTrieDataEncoder: Encoder[PersistedTrieData] = Encoder.instance { data =>
    Json.obj(
      "trie" -> data.trie.asJson,
      "state" -> data.state.asJson
    )
  }

  implicit val persistedTrieDataDecoder: Decoder[PersistedTrieData] = Decoder.instance { cursor =>
    for {
      trie <- cursor.downField("trie").as[MerklePatriciaTrie]
      state <- cursor.downField("state").as[Map[Hex, Json]]
    } yield PersistedTrieData(trie, state)
  }
}

class MerklePatriciaTrieLocalFileSystemStorage[F[_]: Async: JsonSerializer](
  path: Path,
  cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make
) extends SerializableLocalFileSystemStorage[F, MerklePatriciaTrieStorage.PersistedTrieData](path) {
  import MerklePatriciaTrieStorage._

  private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  override def deserializeFallback(bytes: Array[Byte]): Either[Throwable, PersistedTrieData] =
    io.circe.parser
      .decode[PersistedTrieData](new String(bytes, "UTF-8"))
      .leftMap(e => new RuntimeException(s"Failed to deserialize trie: ${e.getMessage}"))

  private def toOrdinalName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString

  def writeTrie(ordinal: SnapshotOrdinal, trie: MerklePatriciaTrie, state: Map[Hex, Json]): F[Unit] = {
    val data = PersistedTrieData(trie, state)
    for {
      _ <- write(toOrdinalName(ordinal), data)
    } yield ()
  }

  def readTrie(ordinal: SnapshotOrdinal): F[Option[(MerklePatriciaTrie, Map[Hex, Json])]] =
    for {
      result <- read(toOrdinalName(ordinal))
      mapped = result.map(data => (data.trie, data.state))
    } yield mapped

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

object MerklePatriciaTrieLocalFileSystemStorage {

  def make[F[_]: Async: JsonSerializer](path: Path): F[MerklePatriciaTrieLocalFileSystemStorage[F]] =
    (new MerklePatriciaTrieLocalFileSystemStorage[F](path)).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }

  def make[F[_]: Async: JsonSerializer](
    path: Path,
    cutoffLogic: OrdinalCutoff
  ): F[MerklePatriciaTrieLocalFileSystemStorage[F]] =
    (new MerklePatriciaTrieLocalFileSystemStorage[F](path, cutoffLogic)).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
