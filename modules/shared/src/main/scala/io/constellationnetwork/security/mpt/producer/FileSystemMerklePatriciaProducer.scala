package io.constellationnetwork.security.mpt.producer

import java.security.MessageDigest

import cats.Parallel
import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.cutoff.OrdinalCutoff
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver
import io.constellationnetwork.security.mpt.storages.MptStateStorage

import fs2.Stream
import fs2.io.file.Path
import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Stateful MPT producer with filesystem persistence and incremental updates.
  *
  * Design:
  *   - Data is stored in stateRef (source of truth)
  *   - Trie is cached in trieRef and updated incrementally
  *   - Pending changes are tracked for incremental updates
  *   - Full rebuild only when no cached trie exists
  */
class FileSystemMerklePatriciaProducer[F[_]: Async: Parallel: Hasher: JsonSerializer](
  stateRef: Ref[F, Map[Hex, Array[Byte]]],
  trieRef: Ref[F, Option[MerklePatriciaTrie]],
  pendingInsertsRef: Ref[F, Map[Hex, Array[Byte]]],
  pendingRemovesRef: Ref[F, List[Hex]],
  storage: MptStateStorage[F],
  rootHashCacheRef: Ref[F, Map[SnapshotOrdinal, MptRoot]],
  lastBuiltOrdinalRef: Ref[F, Option[SnapshotOrdinal]]
) extends StatefulWithPersistenceMerklePatriciaProducer[F] {

  private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)
  private val parallelProducer: ParallelMerklePatriciaProducer[F] = ParallelMerklePatriciaProducer[F]
  private val BatchSize = 5000
  private val MaxCacheSize = 50

  override def getProver: F[MerklePatriciaSingleInclusionProver[F]] =
    build.flatMap {
      case Right(trie) => parallelProducer.getProver(trie)
      case Left(err)   => Async[F].raiseError(err)
    }

  override def entries: F[Map[Hex, Array[Byte]]] =
    stateRef.get

  def entriesAsJson: F[Map[Hex, Json]] =
    stateRef.get.flatMap { state =>
      state.toList.traverse {
        case (k, bytes) =>
          JsonSerializer[F].deserialize[Json](bytes).flatMap {
            case Right(json) => (k -> json).pure[F]
            case Left(err)   => Async[F].raiseError[(Hex, Json)](err)
          }
      }.map(_.toMap)
    }

  def getAsJson(key: Hex): F[Option[Json]] =
    stateRef.get.flatMap { state =>
      state.get(key).traverse { bytes =>
        JsonSerializer[F].deserialize[Json](bytes).flatMap {
          case Right(json) => json.pure[F]
          case Left(err)   => Async[F].raiseError[Json](err)
        }
      }
    }

  override def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      currentTrie <- trieRef.get
      pendingInserts <- pendingInsertsRef.get
      pendingRemoves <- pendingRemovesRef.get
      state <- stateRef.get

      result <- (currentTrie, pendingInserts.isEmpty, pendingRemoves.isEmpty) match {
        case (_, _, _) if state.isEmpty =>
          (OperationError("Cannot build trie with no entries"): MerklePatriciaError)
            .asLeft[MerklePatriciaTrie]
            .pure[F]

        case (Some(trie), true, true) =>
          logger.debug("[MPT] Returning cached trie (no pending changes)") >>
            trie.asRight[MerklePatriciaError].pure[F]

        case (Some(_), _, false) =>
          // Pending removes -> canonical from-scratch rebuild, NOT incremental.
          //
          // The incremental remove path (IncrementalTrieOps.removeMultiple) can leave a trie that
          // is logically correct but STRUCTURALLY non-canonical for some branch/extension collapse
          // shapes -- it produces a different root hash than a from-scratch build of the same final
          // key-set. Inserts are unaffected (proven equal by MptInsertionOrderDeterminismSuite), so
          // only the remove path is fragile here.
          //
          // This matters because recovery (MptStore.syncFull) rebuilds from scratch (fullBuild) and
          // then validates its root against the incrementally-built, consensus-signed root. When an
          // ordinal had removes, those two roots diverged -> StateProofValidator "StateProof Broken"
          // -> the recovering node can never converge (observed: fork-recovery gl0-2 stuck in
          // WaitingForDownload, InvalidStateProof at the same ordinal forever). Routing
          // remove-bearing ordinals through the canonical fullBuild makes the signed root match what
          // recovery reconstructs. Cost: a full rebuild on remove ordinals; insert-only ordinals keep
          // the incremental fast path.
          fullBuild(state)

        case (Some(trie), _, _) =>
          applyIncrementalUpdates(trie, pendingInserts, pendingRemoves)

        case (None, _, _) =>
          fullBuild(state)
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

  private def fullBuild(state: Map[Hex, Array[Byte]]): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      _ <- logger.info(s"[MPT] Full build from ${state.size} entries (no cached trie)")
      result <- parallelProducer.createFromBytes(state).attempt.flatMap {
        case Right(trie) =>
          for {
            _ <- trieRef.set(Some(trie))
            _ <- pendingInsertsRef.set(Map.empty)
            _ <- pendingRemovesRef.set(List.empty)
            _ <- logger.info(s"[MPT] Full build completed")
          } yield trie.asRight[MerklePatriciaError]
        case Left(e) =>
          (OperationError(e.getMessage): MerklePatriciaError).asLeft[MerklePatriciaTrie].pure[F]
      }
    } yield result

  private def applyIncrementalUpdates(
    currentTrie: MerklePatriciaTrie,
    inserts: Map[Hex, Array[Byte]],
    removes: List[Hex]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      _ <- logger.debug(s"[MPT] Applying incremental updates: ${inserts.size} inserts, ${removes.size} removes")

      // Sort removes by CompactNibblePath ordering for deterministic trie structure
      sortedRemoves = removes.sortBy(hex => CompactNibblePath.fromHexString(hex.value))

      afterRemoves <-
        if (removes.isEmpty) currentTrie.rootNode.pure[F]
        else IncrementalTrieOps.removeMultiple[F](currentTrie.rootNode, sortedRemoves)

      result <-
        if (inserts.isEmpty) {
          val newTrie = MerklePatriciaTrie(afterRemoves)
          for {
            _ <- trieRef.set(Some(newTrie))
            _ <- pendingInsertsRef.set(Map.empty)
            _ <- pendingRemovesRef.set(List.empty)
            _ <- logger.debug(s"[MPT] Incremental update completed")
          } yield newTrie.asRight[MerklePatriciaError]
        } else {
          for {
            // Batch hash computation for better parallelism on large inserts
            insertEntries <-
              if (inserts.size <= BatchSize) {
                inserts.toList.parTraverse {
                  case (hex, bytes) => Hasher[F].hashBytes(bytes).map(hash => (hex, hash))
                }
              } else {
                // Process in parallel batches and flatten
                val batches = inserts.toList.grouped(BatchSize).toList
                batches.parTraverse { batch =>
                  batch.parTraverse {
                    case (hex, bytes) => Hasher[F].hashBytes(bytes).map(hash => (hex, hash))
                  }
                }.map(_.flatten)
              }
            // Sort by CompactNibblePath ordering to match full-build insertion order (deterministic trie structure)
            sortedInsertEntries = insertEntries.sortBy { case (hex, _) => CompactNibblePath.fromHexString(hex.value) }
            finalRoot <- IncrementalTrieOps.insertMultiple[F](afterRemoves, sortedInsertEntries)
            newTrie = MerklePatriciaTrie(finalRoot)
            _ <- trieRef.set(Some(newTrie))
            _ <- pendingInsertsRef.set(Map.empty)
            _ <- pendingRemovesRef.set(List.empty)
            _ <- logger.debug(
              s"[MPT] Incremental update completed"
            )
          } yield newTrie.asRight[MerklePatriciaError]
        }
    } yield result

  override def insert[A: Encoder](data: Map[Hex, A]): F[Either[MerklePatriciaError, Unit]] =
    if (data.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else
      for {
        _ <- logger.debug(s"[MPT] Inserting ${data.size} entries")
        byteEntries <- data.toList.traverse {
          case (k, v) => JsonSerializer[F].serialize(v.asJson).map(k -> _)
        }.map(_.toMap)
        _ <- stateRef.update(_ ++ byteEntries)
        _ <- pendingRemovesRef.update(_.filterNot(byteEntries.contains))
        _ <- pendingInsertsRef.update(_ ++ byteEntries)
      } yield ().asRight[MerklePatriciaError]

  def insertBytes(data: Map[Hex, Array[Byte]]): F[Either[MerklePatriciaError, Unit]] =
    if (data.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else
      for {
        _ <- logger.debug(s"[MPT] Inserting ${data.size} byte entries")
        _ <- stateRef.update(_ ++ data)
        _ <- pendingRemovesRef.update(_.filterNot(data.contains))
        _ <- pendingInsertsRef.update(_ ++ data)
      } yield ().asRight[MerklePatriciaError]

  override def update[A: Encoder](key: Hex, value: A): F[Either[MerklePatriciaError, Unit]] =
    stateRef.get.flatMap { state =>
      if (!state.contains(key))
        (OperationError(s"Key not found: $key"): MerklePatriciaError).asLeft[Unit].pure[F]
      else
        for {
          bytes <- JsonSerializer[F].serialize(value.asJson)
          _ <- stateRef.update(_ + (key -> bytes))
          _ <- pendingInsertsRef.update(_ + (key -> bytes))
        } yield ().asRight[MerklePatriciaError]
    }

  override def remove(keys: List[Hex]): F[Either[MerklePatriciaError, Unit]] =
    if (keys.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else
      for {
        _ <- logger.debug(s"[MPT] Removing ${keys.size} entries")
        _ <- stateRef.update(_ -- keys)
        _ <- pendingInsertsRef.update(_ -- keys)
        _ <- pendingRemovesRef.update(existing => (existing ++ keys).distinct)
      } yield ().asRight[MerklePatriciaError]

  override def clear: F[Unit] =
    logger.info("[MPT] Clearing state") >>
      stateRef.set(Map.empty) >>
      trieRef.set(None) >>
      pendingInsertsRef.set(Map.empty) >>
      pendingRemovesRef.set(List.empty) >>
      rootHashCacheRef.set(Map.empty) >>
      lastBuiltOrdinalRef.set(None)

  override def buildHexMap(data: Map[GlobalStateKey, Json]): F[Map[Hex, Array[Byte]]] =
    if (data.size <= BatchSize)
      data.toList.parTraverse {
        case (key, value) =>
          for {
            hex <- GlobalStateKey.toHex[F](key)
            bytes <- JsonSerializer[F].serialize(value)
          } yield hex -> bytes
      }.map(_.toMap)
    else {
      // Process all batches in parallel and combine at the end
      // This avoids O(n) map concatenation per batch
      val batches = data.toList.grouped(BatchSize).toList
      batches.parTraverse { batch =>
        batch.parTraverse {
          case (key, value) =>
            for {
              hex <- GlobalStateKey.toHex[F](key)
              bytes <- JsonSerializer[F].serialize(value)
            } yield hex -> bytes
        }
      }
        .map(_.flatten.toMap)
    }

  override def persist(ordinal: SnapshotOrdinal): F[Unit] =
    for {
      state <- stateRef.get
      _ <-
        if (state.nonEmpty)
          storage.writeState(ordinal, state).attempt.flatMap {
            case Right(_) => applyCutoff(ordinal)
            case Left(err) =>
              logger.error(err)(s"[MPT] Failed to write state for ordinal=$ordinal, applying cutoff anyway") >>
                applyCutoff(ordinal)
          }
        else logger.warn(s"[MPT] Cannot persist: no state")
    } yield ()

  override def load(ordinal: SnapshotOrdinal): F[Boolean] =
    for {
      _ <- logger.info(s"[MPT] Loading state for ordinal=$ordinal")

      loaded <- storage.readState(ordinal).flatMap {
        case Some(state) =>
          for {
            _ <- logger.info(s"[MPT] Found ${state.size} entries")
            _ <- stateRef.set(state)
            _ <- trieRef.set(None)
            _ <- pendingInsertsRef.set(Map.empty)
            _ <- pendingRemovesRef.set(List.empty)
            _ <- logger.info(s"[MPT] State loaded")
          } yield true

        case None =>
          logger.info(s"[MPT] No state found for ordinal=$ordinal") >> false.pure[F]
      }
    } yield loaded

  def loadOrBuild(
    ordinal: SnapshotOrdinal,
    buildData: => F[Map[Hex, Array[Byte]]]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    load(ordinal).flatMap {
      case true => buildForOrdinal(ordinal)
      case false =>
        for {
          _ <- logger.info("[MPT] Building from provided data")
          data <- buildData
          _ <- stateRef.set(data)
          _ <- trieRef.set(None)
          _ <- pendingInsertsRef.set(Map.empty)
          _ <- pendingRemovesRef.set(List.empty)
          result <- buildForOrdinal(ordinal)
        } yield result
    }

  override def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
    logger.info(s"[MPT] Deleting above the ordinal=$ordinal") >> storage.deleteAbove(ordinal)

  override def listStoredOrdinals: F[Stream[F, SnapshotOrdinal]] =
    storage.listStoredOrdinals.map(Stream.emits)

  override def applyCutoff(currentOrdinal: SnapshotOrdinal): F[Unit] =
    logger.info(s"[MPT] Applying cutoff at ordinal=$currentOrdinal") >> storage.applyCutoff(currentOrdinal)

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

object FileSystemMerklePatriciaProducer {

  def make[F[_]: Async: Parallel: Hasher: JsonSerializer](
    path: Path,
    initial: Map[Hex, Array[Byte]] = Map.empty
  ): F[FileSystemMerklePatriciaProducer[F]] =
    for {
      stateRef <- Ref.of[F, Map[Hex, Array[Byte]]](initial)
      trieRef <- Ref.of[F, Option[MerklePatriciaTrie]](None)
      pendingInsertsRef <- Ref.of[F, Map[Hex, Array[Byte]]](Map.empty)
      pendingRemovesRef <- Ref.of[F, List[Hex]](List.empty)
      storage <- MptStateStorage.make[F](path)
      rootHashCacheRef <- Ref.of[F, Map[SnapshotOrdinal, MptRoot]](Map.empty)
      lastBuiltOrdinalRef <- Ref.of[F, Option[SnapshotOrdinal]](None)
    } yield
      new FileSystemMerklePatriciaProducer[F](
        stateRef,
        trieRef,
        pendingInsertsRef,
        pendingRemovesRef,
        storage,
        rootHashCacheRef,
        lastBuiltOrdinalRef
      )

  def make[F[_]: Async: Parallel: Hasher: JsonSerializer](
    path: Path,
    cutoffLogic: OrdinalCutoff,
    initial: Map[Hex, Array[Byte]]
  ): F[FileSystemMerklePatriciaProducer[F]] =
    for {
      stateRef <- Ref.of[F, Map[Hex, Array[Byte]]](initial)
      trieRef <- Ref.of[F, Option[MerklePatriciaTrie]](None)
      pendingInsertsRef <- Ref.of[F, Map[Hex, Array[Byte]]](Map.empty)
      pendingRemovesRef <- Ref.of[F, List[Hex]](List.empty)
      storage <- MptStateStorage.make[F](path, cutoffLogic)
      rootHashCacheRef <- Ref.of[F, Map[SnapshotOrdinal, MptRoot]](Map.empty)
      lastBuiltOrdinalRef <- Ref.of[F, Option[SnapshotOrdinal]](None)
    } yield
      new FileSystemMerklePatriciaProducer[F](
        stateRef,
        trieRef,
        pendingInsertsRef,
        pendingRemovesRef,
        storage,
        rootHashCacheRef,
        lastBuiltOrdinalRef
      )
}
