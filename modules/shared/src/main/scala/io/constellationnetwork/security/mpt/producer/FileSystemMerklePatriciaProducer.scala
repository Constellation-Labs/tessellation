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
import io.constellationnetwork.security.mpt.storages.MerklePatriciaTrieLocalFileSystemStorage

import fs2.Stream
import fs2.io.file.Path
import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.typelevel.log4cats.slf4j.Slf4jLogger

class FileSystemMerklePatriciaProducer[F[_]: Async: Parallel: Hasher](
  stateRef: Ref[F, Map[Hex, Json]],
  trieRef: Ref[F, Option[MerklePatriciaTrie]],
  pendingInsertsRef: Ref[F, Map[Hex, Json]],
  pendingRemovesRef: Ref[F, List[Hex]],
  storage: MerklePatriciaTrieLocalFileSystemStorage[F]
) extends StatefulWithPersistenceMerklePatriciaProducer[F] {

  private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)
  private val parallelProducer: ParallelMerklePatriciaProducer[F] = ParallelMerklePatriciaProducer[F]

  private val BatchSize = 5000
  private val LogProgressEvery = 50000

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
          logger.info("Performing full build of MPT") >>
            fullBuild

        case (Some(trie), true, true) =>
          logger.debug("Unchanged MPT") >>
            trie.asRight[MerklePatriciaError].pure[F]

        case (Some(trie), _, _) =>
          logger.info("Performing incremental build of MPT") >>
            incrementalUpdate(trie, pendingInserts, pendingRemoves)
      }
    } yield result

  private def fullBuild: F[Either[MerklePatriciaError, MerklePatriciaTrie]] = {
    val emptyError: Either[MerklePatriciaError, MerklePatriciaTrie] =
      OperationError("Cannot build trie with no entries").asLeft[MerklePatriciaTrie]

    for {
      start <- Async[F].realTime
      currentEntries <- entries
      _ <- logger.info(s"Full build starting with ${currentEntries.size} entries")

      result <-
        if (currentEntries.isEmpty) {
          emptyError.pure[F]
        } else {
          parallelProducer.create(currentEntries).attempt.flatMap {
            case Right(trie) =>
              (trieRef.set(Some(trie)) >>
                pendingInsertsRef.set(Map.empty) >>
                pendingRemovesRef.set(List.empty)).as(trie.asRight[MerklePatriciaError])
            case Left(e) =>
              val err: Either[MerklePatriciaError, MerklePatriciaTrie] =
                OperationError(e.getMessage).asLeft[MerklePatriciaTrie]
              err.pure[F]
          }
        }

      end <- Async[F].realTime
      _ <- logger.info(s"Full build completed in ${(end - start).toMillis}ms")
    } yield result
  }

  private def incrementalUpdate(
    currentTrie: MerklePatriciaTrie,
    inserts: Map[Hex, Json],
    removes: List[Hex]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      start <- Async[F].realTime
      _ <- logger.info(s"Incremental update: inserts=${inserts.size}, removes=${removes.size}")

      afterRemoves <-
        if (removes.isEmpty) currentTrie.asRight[MerklePatriciaError].pure[F]
        else parallelProducer.remove(currentTrie, removes)

      result <- afterRemoves match {
        case Left(err) =>
          val errResult: Either[MerklePatriciaError, MerklePatriciaTrie] = err.asLeft[MerklePatriciaTrie]
          errResult.pure[F]
        case Right(trieAfterRemoves) =>
          if (inserts.isEmpty) {
            (trieRef.set(Some(trieAfterRemoves)) >>
              pendingInsertsRef.set(Map.empty) >>
              pendingRemovesRef.set(List.empty)).as(trieAfterRemoves.asRight[MerklePatriciaError])
          } else {
            parallelProducer.insert(trieAfterRemoves, inserts).flatMap {
              case Left(err) =>
                val errResult: Either[MerklePatriciaError, MerklePatriciaTrie] = err.asLeft[MerklePatriciaTrie]
                errResult.pure[F]
              case Right(finalTrie) =>
                (trieRef.set(Some(finalTrie)) >>
                  pendingInsertsRef.set(Map.empty) >>
                  pendingRemovesRef.set(List.empty)).as(finalTrie.asRight[MerklePatriciaError])
            }
          }
      }

      end <- Async[F].realTime
      _ <- logger.info(s"Incremental update completed in ${(end - start).toMillis}ms")
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
      if (!state.contains(key)) {
        val err: Either[MerklePatriciaError, Unit] = OperationError(s"Key not found for update: $key").asLeft[Unit]
        err.pure[F]
      } else {
        for {
          _ <- stateRef.update(_ + (key -> value.asJson))
          _ <- pendingInsertsRef.update(_ + (key -> value.asJson))
        } yield ().asRight[MerklePatriciaError]
      }
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

  override def buildHexMap(data: Map[GlobalStateKey, Json]): F[Map[Hex, Json]] = {
    val totalSize = data.size

    if (totalSize <= BatchSize) {
      data.toList.parTraverse {
        case (key, value) => GlobalStateKey.toHex[F](key).map(_ -> value)
      }.map(_.toMap)
    } else {
      for {
        result <- data.toList
          .grouped(BatchSize)
          .toList
          .zipWithIndex
          .foldLeftM(Map.empty[Hex, Json]) {
            case (acc, (batch, batchIdx)) =>
              for {
                batchResult <- batch.parTraverse {
                  case (key, value) => GlobalStateKey.toHex[F](key).map(_ -> value)
                }
                newAcc = acc ++ batchResult.toMap
                _ <- Async[F].cede
              } yield newAcc
          }

        end <- Async[F].realTime
      } yield result
    }
  }

  /** Persist the current trie and state to disk.
    */
  override def persist(ordinal: SnapshotOrdinal): F[Unit] =
    for {
      currentTrie <- trieRef.get
      currentState <- stateRef.get

      _ <- currentTrie match {
        case Some(trie) =>
          storage.writeTrie(ordinal, trie, currentState)
        case None =>
          logger.warn(s"No trie to persist for ordinal=$ordinal, skipping")
      }
    } yield ()

  /** Load trie and state from disk for the given ordinal. Returns true if successfully loaded, false if no data found.
    */
  override def load(ordinal: SnapshotOrdinal): F[Boolean] =
    for {
      start <- Async[F].realTime
      _ <- logger.info(s"Attempting to load trie for ordinal=$ordinal")

      loaded <- storage.readTrie(ordinal).flatMap {
        case Some((trie, state)) =>
          for {
            _ <- logger.info(s"Found persisted trie with ${state.size} entries")
            _ <- stateRef.set(state)
            _ <- trieRef.set(Some(trie))
            _ <- pendingInsertsRef.set(Map.empty)
            _ <- pendingRemovesRef.set(List.empty)
            end <- Async[F].realTime
            _ <- logger.info(s"Trie loaded successfully in ${(end - start).toMillis}ms, rootHash=${trie.rootHash.value}")
          } yield true

        case None =>
          logger.info(s"No persisted trie found for ordinal=$ordinal") >>
            false.pure[F]
      }
    } yield loaded

  /** Try to load trie from disk for the given ordinal. If not found or load fails, populate state from provided data and build the trie.
    *
    * @param ordinal
    *   The ordinal to try loading from
    * @param buildData
    *   Function that provides the data to build from if load fails
    * @return
    *   The built or loaded trie
    */
  def loadOrBuild(
    ordinal: SnapshotOrdinal,
    buildData: => F[Map[Hex, Json]]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      start <- Async[F].realTime
      _ <- logger.info(s"Attempting to load trie for ordinal=$ordinal")

      result <- load(ordinal).flatMap {
        case true =>
          // Successfully loaded from disk
          for {
            end <- Async[F].realTime
            _ <- logger.info(s"Trie loaded from disk in ${(end - start).toMillis}ms")
            trie <- trieRef.get
          } yield trie.toRight(OperationError("Trie was loaded but ref is empty"): MerklePatriciaError)

        case false =>
          for {
            _ <- logger.info(s"No persisted trie found, building from data...")
            data <- buildData
            _ <- logger.info(s"Got ${data.size} entries to build from")
            _ <- stateRef.set(data)
            _ <- trieRef.set(None)
            _ <- pendingInsertsRef.set(Map.empty)
            _ <- pendingRemovesRef.set(List.empty)
            buildResult <- build
            end <- Async[F].realTime
            _ <- logger.info(s"Build completed in ${(end - start).toMillis}ms")
          } yield buildResult
      }
    } yield result

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
      storage <- MerklePatriciaTrieLocalFileSystemStorage.make[F](path)
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
      storage <- MerklePatriciaTrieLocalFileSystemStorage.make[F](path, cutoffLogic)
    } yield new FileSystemMerklePatriciaProducer[F](stateRef, trieRef, pendingInsertsRef, pendingRemovesRef, storage)
}
