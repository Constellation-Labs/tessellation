package io.constellationnetwork.security.mpt.storages

import java.util.Base64

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

object MerklePatriciaTrieStorage {

  /** Container for persisting both the trie and the state map together. State is stored as Base64-encoded bytes to avoid JSON parsing
    * overhead.
    */
  case class PersistedTrieData(
    trie: MerklePatriciaTrie,
    state: Map[Hex, Array[Byte]]
  )

  // Custom encoder/decoder for Array[Byte] as Base64
  private implicit val byteArrayEncoder: Encoder[Array[Byte]] =
    Encoder.encodeString.contramap(bytes => Base64.getEncoder.encodeToString(bytes))

  private implicit val byteArrayDecoder: Decoder[Array[Byte]] =
    Decoder.decodeString.emap { str =>
      try Right(Base64.getDecoder.decode(str))
      catch { case e: IllegalArgumentException => Left(s"Invalid Base64: ${e.getMessage}") }
    }

  // Encoder/decoder for Map[Hex, Array[Byte]]
  private implicit val stateMapEncoder: Encoder[Map[Hex, Array[Byte]]] =
    Encoder.instance { map =>
      Json.obj(
        map.toList.map {
          case (hex, bytes) =>
            hex.value -> Json.fromString(Base64.getEncoder.encodeToString(bytes))
        }: _*
      )
    }

  private implicit val stateMapDecoder: Decoder[Map[Hex, Array[Byte]]] =
    Decoder.instance { cursor =>
      cursor.as[Map[String, String]].map { stringMap =>
        stringMap.map {
          case (hexStr, base64) =>
            Hex(hexStr) -> Base64.getDecoder.decode(base64)
        }
      }
    }

  implicit val persistedTrieDataEncoder: Encoder[PersistedTrieData] = Encoder.instance { data =>
    Json.obj(
      "trie" -> data.trie.asJson,
      "state" -> data.state.asJson
    )
  }

  implicit val persistedTrieDataDecoder: Decoder[PersistedTrieData] = Decoder.instance { cursor =>
    for {
      trie <- cursor.downField("trie").as[MerklePatriciaTrie]
      state <- cursor.downField("state").as[Map[Hex, Array[Byte]]]
    } yield PersistedTrieData(trie, state)
  }
}

class MerklePatriciaTrieLocalFileSystemStorage[F[_]: Async: JsonSerializer](
  path: Path,
  cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make
) extends SerializableLocalFileSystemStorage[F, MerklePatriciaTrieStorage.PersistedTrieData](path) {
  import MerklePatriciaTrieStorage._

  override def deserializeFallback(bytes: Array[Byte]): Either[Throwable, PersistedTrieData] =
    io.circe.parser
      .decode[PersistedTrieData](new String(bytes, "UTF-8"))
      .leftMap(e => new RuntimeException(s"Failed to deserialize trie: ${e.getMessage}"))

  private def toOrdinalName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString

  def writeTrie(ordinal: SnapshotOrdinal, trie: MerklePatriciaTrie, state: Map[Hex, Array[Byte]]): F[Unit] = {
    val data = PersistedTrieData(trie, state)
    for {
      _ <- write(toOrdinalName(ordinal), data)
    } yield ()
  }

  def readTrie(ordinal: SnapshotOrdinal): F[Option[(MerklePatriciaTrie, Map[Hex, Array[Byte]])]] =
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
    new MerklePatriciaTrieLocalFileSystemStorage[F](path).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }

  def make[F[_]: Async: JsonSerializer](
    path: Path,
    cutoffLogic: OrdinalCutoff
  ): F[MerklePatriciaTrieLocalFileSystemStorage[F]] =
    new MerklePatriciaTrieLocalFileSystemStorage[F](path, cutoffLogic).pure[F].flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
