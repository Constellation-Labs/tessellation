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

class FileSystemMerklePatriciaProducer[F[_]: Async: Parallel: Hasher](
  stateRef: Ref[F, Map[Hex, Json]],
  trieRef: Ref[F, Option[MerklePatriciaTrie]],
  pendingInsertsRef: Ref[F, Map[Hex, Json]],
  pendingRemovesRef: Ref[F, List[Hex]],
  storage: MerklePatriciaHexMapLocalFileSystemStorage[F]
) extends StatefulWithPersistenceMerklePatriciaProducer[F] {

  private val parallelProducer: ParallelMerklePatriciaProducer[F] = ParallelMerklePatriciaProducer[F]

  override def getProver: F[MerklePatriciaSingleInclusionProver[F]] =
    build.flatMap {
      case Right(trie) => parallelProducer.getProver(trie)
      case Left(err)   => Async[F].raiseError(err)
    }

  override def entries: F[Map[Hex, Json]] =
    stateRef.get

  override def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      currentTrie <- trieRef.get
      pendingInserts <- pendingInsertsRef.get
      pendingRemoves <- pendingRemovesRef.get

      result <- (currentTrie, pendingInserts.isEmpty, pendingRemoves.isEmpty) match {
        case (None, _, _) =>
          fullBuild

        case (Some(trie), true, true) =>
          trie.asRight[MerklePatriciaError].pure[F]

        case (Some(trie), _, _) =>
          incrementalUpdate(trie, pendingInserts, pendingRemoves)
      }
    } yield result

  private def fullBuild: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    entries.flatMap { currentEntries =>
      if (currentEntries.isEmpty)
        OperationError("Cannot build trie with no entries").asLeft[MerklePatriciaTrie].pure[F].widen
      else
        parallelProducer.create(currentEntries).attempt.flatMap {
          case Right(trie) =>
            (trieRef.set(Some(trie)) >>
              pendingInsertsRef.set(Map.empty) >>
              pendingRemovesRef.set(List.empty)).as(trie.asRight[MerklePatriciaError])
          case Left(e) =>
            OperationError(e.getMessage).asLeft[MerklePatriciaTrie].pure[F].widen
        }
    }

  private def incrementalUpdate(
    currentTrie: MerklePatriciaTrie,
    inserts: Map[Hex, Json],
    removes: List[Hex]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      afterRemoves <-
        if (removes.isEmpty) currentTrie.asRight[MerklePatriciaError].pure[F]
        else parallelProducer.remove(currentTrie, removes)

      result <- afterRemoves match {
        case Left(err) => err.asLeft[MerklePatriciaTrie].pure[F].widen
        case Right(trieAfterRemoves) =>
          if (inserts.isEmpty) {
            (trieRef.set(Some(trieAfterRemoves)) >>
              pendingInsertsRef.set(Map.empty) >>
              pendingRemovesRef.set(List.empty)).as(trieAfterRemoves.asRight[MerklePatriciaError])
          } else {
            parallelProducer.insert(trieAfterRemoves, inserts).flatMap {
              case Left(err) => err.asLeft[MerklePatriciaTrie].pure[F].widen
              case Right(finalTrie) =>
                (trieRef.set(Some(finalTrie)) >>
                  pendingInsertsRef.set(Map.empty) >>
                  pendingRemovesRef.set(List.empty)).as(finalTrie.asRight[MerklePatriciaError])
            }
          }
      }
    } yield result

  override def insert[A: Encoder](data: Map[Hex, A]): F[Either[MerklePatriciaError, Unit]] =
    if (data.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else {
      val jsonEntries = data.map { case (k, v) => k -> v.asJson }
      for {
        _ <- stateRef.update(_ ++ jsonEntries)
        _ <- pendingRemovesRef.update(_.filterNot(jsonEntries.contains))
        _ <- pendingInsertsRef.update(_ ++ jsonEntries)
      } yield ().asRight[MerklePatriciaError]
    }

  override def update[A: Encoder](key: Hex, value: A): F[Either[MerklePatriciaError, Unit]] =
    stateRef.get.flatMap { state =>
      if (!state.contains(key))
        OperationError(s"Key not found for update: $key").asLeft[Unit].pure[F].widen
      else
        for {
          _ <- stateRef.update(_ + (key -> value.asJson))
          _ <- pendingInsertsRef.update(_ + (key -> value.asJson))
        } yield ().asRight[MerklePatriciaError]
    }

  override def remove(keys: List[Hex]): F[Either[MerklePatriciaError, Unit]] =
    if (keys.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else
      for {
        _ <- stateRef.update(_ -- keys)
        _ <- pendingInsertsRef.update(_ -- keys)
        _ <- pendingRemovesRef.update(existing => (existing ++ keys).distinct)
      } yield ().asRight[MerklePatriciaError]

  override def clear: F[Unit] =
    stateRef.set(Map.empty) >>
      trieRef.set(None) >>
      pendingInsertsRef.set(Map.empty) >>
      pendingRemovesRef.set(List.empty)

  override def buildHexMap(data: Map[GlobalStateKey, Json]): F[Map[Hex, Json]] =
    data.toList.parTraverse {
      case (key, value) => GlobalStateKey.toHex[F](key).map(_ -> value)
    }.map(_.toMap)

  override def persist(ordinal: SnapshotOrdinal): F[Unit] =
    stateRef.get.flatMap(storage.write(ordinal, _))

  override def load(ordinal: SnapshotOrdinal): F[Boolean] =
    storage.read(ordinal).flatMap {
      case Some(hexMap) =>
        (stateRef.set(hexMap) >>
          trieRef.set(None) >>
          pendingInsertsRef.set(Map.empty) >>
          pendingRemovesRef.set(List.empty)).as(true)
      case None => false.pure[F]
    }

  override def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
    storage.deleteAbove(ordinal)

  override def listStoredOrdinals: F[Stream[F, SnapshotOrdinal]] =
    storage.listStoredOrdinals

  override def applyCutoff(currentOrdinal: SnapshotOrdinal): F[Unit] =
    storage.applyCutoff(currentOrdinal)
}

object FileSystemMerklePatriciaProducer {

  def make[F[_]: Async: Parallel: Hasher: JsonSerializer](
    path: Path,
    initial: Map[Hex, Json] = Map.empty
  ): F[FileSystemMerklePatriciaProducer[F]] =
    for {
      stateRef <- Ref.of[F, Map[Hex, Json]](initial)
      trieRef <- Ref.of[F, Option[MerklePatriciaTrie]](None)
      pendingInsertsRef <- Ref.of[F, Map[Hex, Json]](Map.empty)
      pendingRemovesRef <- Ref.of[F, List[Hex]](List.empty)
      storage <- MerklePatriciaHexMapLocalFileSystemStorage.make[F](path)
    } yield new FileSystemMerklePatriciaProducer[F](stateRef, trieRef, pendingInsertsRef, pendingRemovesRef, storage)

  def make[F[_]: Async: Parallel: Hasher: JsonSerializer](
    path: Path,
    cutoffLogic: OrdinalCutoff,
    initial: Map[Hex, Json]
  ): F[FileSystemMerklePatriciaProducer[F]] =
    for {
      stateRef <- Ref.of[F, Map[Hex, Json]](initial)
      trieRef <- Ref.of[F, Option[MerklePatriciaTrie]](None)
      pendingInsertsRef <- Ref.of[F, Map[Hex, Json]](Map.empty)
      pendingRemovesRef <- Ref.of[F, List[Hex]](List.empty)
      storage <- MerklePatriciaHexMapLocalFileSystemStorage.make[F](path, cutoffLogic)
    } yield new FileSystemMerklePatriciaProducer[F](stateRef, trieRef, pendingInsertsRef, pendingRemovesRef, storage)
}
