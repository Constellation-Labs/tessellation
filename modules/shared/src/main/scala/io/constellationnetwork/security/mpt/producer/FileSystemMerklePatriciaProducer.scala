package io.constellationnetwork.security.mpt.producer

import cats.Parallel
import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.cutoff.OrdinalCutoff
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.MerklePatriciaTrie
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver
import io.constellationnetwork.security.mpt.storages.MerklePatriciaHexMapLocalFileSystemStorage

import fs2.Stream
import fs2.io.file.Path
import io.circe.syntax._
import io.circe.{Encoder, Json}

class FileSystemMerklePatriciaProducer[F[_]: Async: Hasher](
  stateRef: Ref[F, Map[Hex, Json]],
  storage: MerklePatriciaHexMapLocalFileSystemStorage[F]
) extends StatefulWithPersistenceMerklePatriciaProducer[F] {

  override def getProver: F[MerklePatriciaSingleInclusionProver[F]] =
    build.flatMap {
      case Right(trie) => MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]
      case Left(err)   => Async[F].raiseError(err)
    }

  override def entries: F[Map[Hex, Json]] =
    stateRef.get

  override def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    entries.flatMap { currentEntries =>
      if (currentEntries.isEmpty)
        OperationError("Cannot build trie with no entries").asLeft[MerklePatriciaTrie].pure[F].widen
      else
        MerklePatriciaTrie.make[F, Json](currentEntries).attempt.map {
          case Right(trie) => trie.asRight[MerklePatriciaError]
          case Left(e)     => OperationError(e.getMessage).asLeft[MerklePatriciaTrie]
        }
    }

  override def insert[A: Encoder](data: Map[Hex, A]): F[Either[MerklePatriciaError, Unit]] =
    if (data.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else {
      val jsonEntries = data.map { case (k, v) => k -> v.asJson }
      stateRef.update(_ ++ jsonEntries).as(().asRight[MerklePatriciaError])
    }

  override def update[A: Encoder](key: Hex, value: A): F[Either[MerklePatriciaError, Unit]] =
    stateRef.get.flatMap { state =>
      if (!state.contains(key))
        OperationError(s"Key not found for update: $key").asLeft[Unit].pure[F].widen
      else
        stateRef.update(_ + (key -> value.asJson)).as(().asRight[MerklePatriciaError])
    }

  override def remove(keys: List[Hex]): F[Either[MerklePatriciaError, Unit]] =
    if (keys.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else stateRef.update(_ -- keys).as(().asRight[MerklePatriciaError])

  override def clear: F[Unit] =
    stateRef.set(Map.empty)

  override def buildHexMap(data: Map[GlobalStateKey, Json])(implicit parallel: Parallel[F]): F[Map[Hex, Json]] =
    data.toList.parTraverse {
      case (key, value) => GlobalStateKey.toHex[F](key).map(_ -> value)
    }.map(_.toMap)

  override def persist(ordinal: SnapshotOrdinal): F[Unit] =
    stateRef.get.flatMap(storage.write(ordinal, _))

  override def load(ordinal: SnapshotOrdinal): F[Boolean] =
    storage.read(ordinal).flatMap {
      case Some(hexMap) => stateRef.set(hexMap).as(true)
      case None         => false.pure[F]
    }

  override def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
    storage.deleteAbove(ordinal)

  override def listStoredOrdinals: F[Stream[F, SnapshotOrdinal]] =
    storage.listStoredOrdinals

  override def applyCutoff(currentOrdinal: SnapshotOrdinal): F[Unit] =
    storage.applyCutoff(currentOrdinal)
}

object FileSystemMerklePatriciaProducer {

  def make[F[_]: Async: Hasher: JsonSerializer](
    path: Path,
    initial: Map[Hex, Json] = Map.empty
  ): F[FileSystemMerklePatriciaProducer[F]] =
    for {
      stateRef <- Ref.of[F, Map[Hex, Json]](initial)
      storage <- MerklePatriciaHexMapLocalFileSystemStorage.make[F](path)
    } yield new FileSystemMerklePatriciaProducer[F](stateRef, storage)

  def make[F[_]: Async: Hasher: JsonSerializer](
    path: Path,
    cutoffLogic: OrdinalCutoff,
    initial: Map[Hex, Json]
  ): F[FileSystemMerklePatriciaProducer[F]] =
    for {
      stateRef <- Ref.of[F, Map[Hex, Json]](initial)
      storage <- MerklePatriciaHexMapLocalFileSystemStorage.make[F](path, cutoffLogic)
    } yield new FileSystemMerklePatriciaProducer[F](stateRef, storage)
}
