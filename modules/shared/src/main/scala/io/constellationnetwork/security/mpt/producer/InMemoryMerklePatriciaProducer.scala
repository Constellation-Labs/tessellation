package io.constellationnetwork.security.mpt.producer

import cats.Parallel
import cats.data.EitherT
import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver
import io.constellationnetwork.security.mpt.{MerklePatriciaTrie, MptRoot}

import io.circe.syntax._
import io.circe.{Encoder, Json}

class InMemoryMerklePatriciaProducer[F[_]: Async: Hasher: Parallel: JsonSerializer](
  stateRef: Ref[F, Map[Hex, Array[Byte]]],
  trieRef: Ref[F, Option[MerklePatriciaTrie]],
  pendingInsertsRef: Ref[F, Map[Hex, Array[Byte]]],
  pendingRemovesRef: Ref[F, List[Hex]],
  rootHashCacheRef: Ref[F, Map[SnapshotOrdinal, MptRoot]],
  lastBuiltOrdinalRef: Ref[F, Option[SnapshotOrdinal]]
) extends StatefulMerklePatriciaProducer[F] {

  private val parallelProducer: ParallelMerklePatriciaProducer[F] = ParallelMerklePatriciaProducer[F]
  private val MaxCacheSize = 50

  override def getProver: F[MerklePatriciaSingleInclusionProver[F]] =
    build.flatMap {
      case Right(trie) => parallelProducer.getProver(trie)
      case Left(err)   => Async[F].raiseError(err)
    }

  override def entries: F[Map[Hex, Array[Byte]]] =
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

  override def buildForOrdinal(ordinal: SnapshotOrdinal): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    build.flatMap {
      case Right(trie) =>
        val rootHash = trie.rootHash
        (cacheRootHash(ordinal, rootHash) >> lastBuiltOrdinalRef.set(Some(ordinal)))
          .as(trie.asRight[MerklePatriciaError])
      case Left(err) =>
        err.asLeft[MerklePatriciaTrie].pure[F]
    }

  override def getRootHashForOrdinal(ordinal: SnapshotOrdinal): F[Option[MptRoot]] =
    rootHashCacheRef.get.map(_.get(ordinal))

  override def getCurrentRootHash: F[Option[MptRoot]] =
    trieRef.get.map(_.map(_.rootHash))

  override def getLastBuiltOrdinal: F[Option[SnapshotOrdinal]] =
    lastBuiltOrdinalRef.get

  private def cacheRootHash(ordinal: SnapshotOrdinal, rootHash: MptRoot): F[Unit] =
    rootHashCacheRef.update { cache =>
      val updated = cache + (ordinal -> rootHash)
      if (updated.size > MaxCacheSize) {
        // Keep only the latest 50 by ordinal
        updated.toList.sortBy(_._1).takeRight(MaxCacheSize).toMap
      } else {
        updated
      }
    }

  private def fullBuild: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      currentEntries <- entries
      result <-
        if (currentEntries.isEmpty) {
          (OperationError("Cannot build trie with no entries"): MerklePatriciaError)
            .asLeft[MerklePatriciaTrie]
            .pure[F]
        } else {
          parallelProducer.createFromBytes(currentEntries).attempt.flatMap {
            case Right(trie) =>
              (trieRef.set(Some(trie)) >>
                pendingInsertsRef.set(Map.empty) >>
                pendingRemovesRef.set(List.empty)).as(trie.asRight[MerklePatriciaError])
            case Left(e) =>
              (OperationError(e.getMessage): MerklePatriciaError)
                .asLeft[MerklePatriciaTrie]
                .pure[F]
          }
        }
    } yield result

  private def incrementalUpdate(
    currentTrie: MerklePatriciaTrie,
    inserts: Map[Hex, Array[Byte]],
    removes: List[Hex]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] = {

    def applyRemoves(trie: MerklePatriciaTrie): EitherT[F, MerklePatriciaError, MerklePatriciaTrie] =
      if (removes.isEmpty) EitherT.rightT(trie)
      else EitherT(parallelProducer.remove(trie, removes))

    def applyInserts(trie: MerklePatriciaTrie): EitherT[F, MerklePatriciaError, MerklePatriciaTrie] =
      if (inserts.isEmpty) EitherT.rightT(trie)
      else EitherT(parallelProducer.insertFromBytes(trie, inserts))

    def clearPending(trie: MerklePatriciaTrie): EitherT[F, MerklePatriciaError, Unit] =
      EitherT.liftF(
        trieRef.set(Some(trie)) >>
          pendingInsertsRef.set(Map.empty) >>
          pendingRemovesRef.set(List.empty)
      )

    (for {
      afterRemoves <- applyRemoves(currentTrie)
      finalTrie <- applyInserts(afterRemoves)
      _ <- clearPending(finalTrie)
    } yield finalTrie).value
  }

  override def insert[A: Encoder](data: Map[Hex, A]): F[Either[MerklePatriciaError, Unit]] =
    if (data.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else {
      for {
        byteEntries <- data.toList.traverse {
          case (k, v) =>
            JsonSerializer[F].serialize(v.asJson).map(k -> _)
        }.map(_.toMap)
        _ <- stateRef.update(_ ++ byteEntries)
        _ <- pendingRemovesRef.update(_.filterNot(byteEntries.contains))
        _ <- pendingInsertsRef.update(_ ++ byteEntries)
      } yield ().asRight[MerklePatriciaError]
    }

  override def insertBytes(data: Map[Hex, Array[Byte]]): F[Either[MerklePatriciaError, Unit]] =
    if (data.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else {
      for {
        _ <- stateRef.update(_ ++ data)
        _ <- pendingRemovesRef.update(_.filterNot(data.contains))
        _ <- pendingInsertsRef.update(_ ++ data)
      } yield ().asRight[MerklePatriciaError]
    }

  override def update[A: Encoder](key: Hex, value: A): F[Either[MerklePatriciaError, Unit]] =
    stateRef.get.flatMap { state =>
      if (!state.contains(key)) {
        (OperationError(s"Key not found for update: $key"): MerklePatriciaError)
          .asLeft[Unit]
          .pure[F]
      } else {
        for {
          bytes <- JsonSerializer[F].serialize(value.asJson)
          _ <- stateRef.update(_ + (key -> bytes))
          _ <- pendingInsertsRef.update(_ + (key -> bytes))
        } yield ().asRight[MerklePatriciaError]
      }
    }

  override def remove(keys: List[Hex]): F[Either[MerklePatriciaError, Unit]] =
    if (keys.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else {
      for {
        _ <- stateRef.update(_ -- keys)
        _ <- pendingInsertsRef.update(_ -- keys)
        _ <- pendingRemovesRef.update(existing => (existing ++ keys).distinct)
      } yield ().asRight[MerklePatriciaError]
    }

  override def clear: F[Unit] =
    stateRef.set(Map.empty) >>
      trieRef.set(None) >>
      pendingInsertsRef.set(Map.empty) >>
      pendingRemovesRef.set(List.empty) >>
      rootHashCacheRef.set(Map.empty) >>
      lastBuiltOrdinalRef.set(None)

  override def buildHexMap(data: Map[GlobalStateKey, Json]): F[Map[Hex, Array[Byte]]] = {
    val BatchSize = 5000

    if (data.size <= BatchSize) {
      data.toList.parTraverse {
        case (key, value) =>
          for {
            hex <- GlobalStateKey.toHex[F](key)
            bytes <- JsonSerializer[F].serialize(value)
          } yield hex -> bytes
      }.map(_.toMap)
    } else {
      data.toList
        .grouped(BatchSize)
        .toList
        .foldLeftM(Map.empty[Hex, Array[Byte]]) { (acc, batch) =>
          for {
            batchResult <- batch.parTraverse {
              case (key, value) =>
                for {
                  hex <- GlobalStateKey.toHex[F](key)
                  bytes <- JsonSerializer[F].serialize(value)
                } yield hex -> bytes
            }
            _ <- Async[F].cede
          } yield acc ++ batchResult.toMap
        }
    }
  }

  override def savepoint: F[ProducerSavepoint[F]] =
    for {
      savedState <- stateRef.get
      savedTrie <- trieRef.get
      savedPendingInserts <- pendingInsertsRef.get
      savedPendingRemoves <- pendingRemovesRef.get
      savedRootHashCache <- rootHashCacheRef.get
      savedLastBuiltOrdinal <- lastBuiltOrdinalRef.get
    } yield
      new ProducerSavepoint[F] {
        def restore: F[Unit] =
          stateRef.set(savedState) >>
            trieRef.set(savedTrie) >>
            pendingInsertsRef.set(savedPendingInserts) >>
            pendingRemovesRef.set(savedPendingRemoves) >>
            rootHashCacheRef.set(savedRootHashCache) >>
            lastBuiltOrdinalRef.set(savedLastBuiltOrdinal)
      }
}

object InMemoryMerklePatriciaProducer {

  def make[F[_]: Async: Hasher: Parallel: JsonSerializer](
    initial: Map[Hex, Array[Byte]] = Map.empty
  ): F[InMemoryMerklePatriciaProducer[F]] =
    for {
      stateRef <- Ref.of[F, Map[Hex, Array[Byte]]](initial)
      trieRef <- Ref.of[F, Option[MerklePatriciaTrie]](None)
      pendingInsertsRef <- Ref.of[F, Map[Hex, Array[Byte]]](Map.empty)
      pendingRemovesRef <- Ref.of[F, List[Hex]](List.empty)
      rootHashCacheRef <- Ref.of[F, Map[SnapshotOrdinal, MptRoot]](Map.empty)
      lastBuiltOrdinalRef <- Ref.of[F, Option[SnapshotOrdinal]](None)
    } yield
      new InMemoryMerklePatriciaProducer[F](
        stateRef,
        trieRef,
        pendingInsertsRef,
        pendingRemovesRef,
        rootHashCacheRef,
        lastBuiltOrdinalRef
      )
}
