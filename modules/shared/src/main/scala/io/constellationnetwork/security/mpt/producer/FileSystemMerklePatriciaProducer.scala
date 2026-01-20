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

class FileSystemMerklePatriciaProducer[F[_]: Async: Parallel: Hasher: JsonSerializer](
  stateRef: Ref[F, Map[Hex, Array[Byte]]],
  trieRef: Ref[F, Option[MerklePatriciaTrie]],
  pendingInsertsRef: Ref[F, Map[Hex, Array[Byte]]],
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

  override def entries: F[Map[Hex, Array[Byte]]] =
    stateRef.get

  /** Get entries as Json (deserializes on demand). */
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

  /** Get a single entry as Json. */
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
          parallelProducer.createFromBytes(currentEntries).attempt.flatMap {
            case Right(trie) =>
              MerklePatriciaTrie.rootHash[F](trie).flatMap {
                case (_, hashedTrie) =>
                  (trieRef.set(Some(hashedTrie)) >>
                    pendingInsertsRef.set(Map.empty) >>
                    pendingRemovesRef.set(List.empty)).as(hashedTrie.asRight[MerklePatriciaError])
              }
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
    inserts: Map[Hex, Array[Byte]],
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
          err.asLeft[MerklePatriciaTrie].pure[F]
        case Right(trieAfterRemoves) =>
          if (inserts.isEmpty) {
            MerklePatriciaTrie.rootHash[F](trieAfterRemoves).flatMap {
              case (_, hashedTrie) =>
                (trieRef.set(Some(hashedTrie)) >>
                  pendingInsertsRef.set(Map.empty) >>
                  pendingRemovesRef.set(List.empty)).as(hashedTrie.asRight[MerklePatriciaError])
            }
          } else {
            parallelProducer.insertFromBytes(trieAfterRemoves, inserts).flatMap {
              case Left(err) =>
                err.asLeft[MerklePatriciaTrie].pure[F]
              case Right(finalTrie) =>
                MerklePatriciaTrie.rootHash[F](finalTrie).flatMap {
                  case (_, hashedTrie) =>
                    (trieRef.set(Some(hashedTrie)) >>
                      pendingInsertsRef.set(Map.empty) >>
                      pendingRemovesRef.set(List.empty)).as(hashedTrie.asRight[MerklePatriciaError])
                }
            }
          }
      }

      end <- Async[F].realTime
      _ <- logger.info(s"Incremental update completed in ${(end - start).toMillis}ms")
    } yield result

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

  /** Insert raw bytes directly (avoids serialization if already have bytes). */
  def insertBytes(data: Map[Hex, Array[Byte]]): F[Either[MerklePatriciaError, Unit]] =
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
        val err: Either[MerklePatriciaError, Unit] = OperationError(s"Key not found for update: $key").asLeft[Unit]
        err.pure[F]
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

  override def buildHexMap(data: Map[GlobalStateKey, Json]): F[Map[Hex, Array[Byte]]] = {
    val totalSize = data.size

    if (totalSize <= BatchSize) {
      data.toList.parTraverse {
        case (key, value) =>
          for {
            hex <- GlobalStateKey.toHex[F](key)
            bytes <- JsonSerializer[F].serialize(value)
          } yield hex -> bytes
      }.map(_.toMap)
    } else {
      for {
        result <- data.toList
          .grouped(BatchSize)
          .toList
          .zipWithIndex
          .foldLeftM(Map.empty[Hex, Array[Byte]]) {
            case (acc, (batch, _)) =>
              for {
                batchResult <- batch.parTraverse {
                  case (key, value) =>
                    for {
                      hex <- GlobalStateKey.toHex[F](key)
                      bytes <- JsonSerializer[F].serialize(value)
                    } yield hex -> bytes
                }
                newAcc = acc ++ batchResult.toMap
                _ <- Async[F].cede
              } yield newAcc
          }
      } yield result
    }
  }

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
            _ <- logger.info(s"Trie loaded successfully in ${(end - start).toMillis}ms")
          } yield true

        case None =>
          logger.info(s"No persisted trie found for ordinal=$ordinal") >>
            false.pure[F]
      }
    } yield loaded

  def loadOrBuild(
    ordinal: SnapshotOrdinal,
    buildData: => F[Map[Hex, Array[Byte]]]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      start <- Async[F].realTime
      _ <- logger.info(s"Attempting to load trie for ordinal=$ordinal")

      result <- load(ordinal).flatMap {
        case true =>
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
    initial: Map[Hex, Array[Byte]] = Map.empty
  ): F[FileSystemMerklePatriciaProducer[F]] =
    for {
      stateRef <- Ref.of[F, Map[Hex, Array[Byte]]](initial)
      trieRef <- Ref.of[F, Option[MerklePatriciaTrie]](None)
      pendingInsertsRef <- Ref.of[F, Map[Hex, Array[Byte]]](Map.empty)
      pendingRemovesRef <- Ref.of[F, List[Hex]](List.empty)
      storage <- MerklePatriciaTrieLocalFileSystemStorage.make[F](path)
    } yield new FileSystemMerklePatriciaProducer[F](stateRef, trieRef, pendingInsertsRef, pendingRemovesRef, storage)

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
      storage <- MerklePatriciaTrieLocalFileSystemStorage.make[F](path, cutoffLogic)
    } yield new FileSystemMerklePatriciaProducer[F](stateRef, trieRef, pendingInsertsRef, pendingRemovesRef, storage)
}
