package io.constellationnetwork.schema.mpt

import cats.Parallel
import cats.effect.std.Semaphore
import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.producer._

import io.circe.{Decoder, Encoder, Json}
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Captured snapshot of an MptStore's internal state. Call `restore` to roll back the store to the state at the time this savepoint was
  * created. Used to undo mutations from failed artifact validation (e.g. stateProof divergence).
  */
trait MptStoreSavepoint[F[_]] {
  def restore: F[Unit]
}

trait MptStore[F[_], K] {
  def get[V: Decoder](key: K): F[Option[V]]
  def getMany[V: Decoder](keys: List[K]): F[Map[K, V]]
  def insert[V: Encoder](key: K, value: V): F[Unit]
  def insert[V: Encoder](entries: Map[K, V]): F[Unit]
  def remove(key: K): F[Unit]
  def remove(keys: List[K]): F[Unit]
  def contains(key: K): F[Boolean]
  def isEmpty: F[Boolean]
  def clear: F[Unit]
  def build(ordinal: SnapshotOrdinal): F[Either[MerklePatriciaError, MerklePatriciaTrie]]
  def sync[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit]
  def syncFull[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit]
  def syncFullIfNeeded[V: Encoder](newState: => F[Map[K, V]], ordinal: SnapshotOrdinal, expectedRoot: Option[Hash] = None): F[Unit]
  def update[V: Encoder](toUpsert: Map[K, V], toRemove: Set[K]): F[Unit]
  def underlying: StatefulMerklePatriciaProducer[F]
  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit]

  /** Capture a snapshot of all internal state (producer state + last synced ordinal). The returned savepoint can restore the store to this
    * exact state, undoing any mutations that occurred after the savepoint was created.
    */
  def savepoint: F[MptStoreSavepoint[F]]
}

object MptStore {

  def make[F[_]: Async: Parallel: Hasher: JsonSerializer, K](
    producer: StatefulMerklePatriciaProducer[F],
    toHex: K => F[Hex]
  ): F[MptStore[F, K]] =
    for {
      lastSyncedOrdinalRef <- Ref.of[F, Option[SnapshotOrdinal]](None)
      // Serializes the heavy mutation methods (syncFull/sync/update/deleteAbove) and the
      // multi-Ref savepoint capture+restore so that concurrent callers (FSM proposal path,
      // download path, state-channel sync) cannot tear the producer's internal state.
      // Today the consensus state machine implicitly prevents overlap by filtering
      // commands during WaitingForDownload / DownloadInProgress, but that invariant is
      // fragile: this Semaphore makes the API self-protecting regardless of caller fiber.
      // Note: insert/remove/clear/build are NOT externally invoked (verified by grep) and
      // are called only from inside the wrapped outer methods, so wrapping them would
      // deadlock. If a new external caller appears, wrap that method too or add an
      // unsafe-internal variant.
      mutationLock <- Semaphore[F](1)
    } yield new Impl[F, K](producer, toHex, lastSyncedOrdinalRef, mutationLock): MptStore[F, K]

  private final class Impl[F[_]: Async: Parallel: Hasher: JsonSerializer, K](
    producer: StatefulMerklePatriciaProducer[F],
    toHex: K => F[Hex],
    lastSyncedOrdinalRef: Ref[F, Option[SnapshotOrdinal]],
    mutationLock: Semaphore[F]
  ) extends MptStore[F, K] {

    private def withLock[A](fa: F[A]): F[A] = mutationLock.permit.use(_ => fa)

    private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)
    private val BatchSize = 5000

    private def persistAsync(ordinal: SnapshotOrdinal): F[Unit] =
      producer match {
        case p: StatefulWithPersistenceMerklePatriciaProducer[F] =>
          Async[F]
            .start(
              p.persist(ordinal).handleErrorWith { err =>
                logger.error(err)(s"[MptStore] Background persist failed for ordinal=$ordinal") >>
                  p.applyCutoff(ordinal).handleErrorWith { cutoffErr =>
                    logger.error(cutoffErr)(s"[MptStore] Cutoff after failed persist also failed for ordinal=$ordinal")
                  }
              }
            )
            .void
        case _ =>
          Async[F].unit
      }

    private def toHexEntries[V: Encoder](data: Map[K, V]): F[Map[Hex, Array[Byte]]] =
      if (data.isEmpty) Map.empty[Hex, Array[Byte]].pure[F]
      else if (data.size <= BatchSize) {
        data.toList.parTraverse {
          case (k, v) =>
            for {
              hex <- toHex(k)
              bytes <- JsonSerializer[F].serialize(v)
            } yield hex -> bytes
        }.map(_.toMap)
      } else {
        // Process all batches in parallel and combine results at the end
        // This avoids O(n) map concatenation per batch
        val batches = data.toList.grouped(BatchSize).toList
        batches.parTraverse { batch =>
          batch.parTraverse {
            case (k, v) =>
              for {
                hex <- toHex(k)
                bytes <- JsonSerializer[F].serialize(v)
              } yield hex -> bytes
          }
        }
          .map(_.flatten.toMap)
      }

    private def deserializeBytes[V: Decoder](bytes: Array[Byte]): F[Option[V]] =
      if (bytes == null || bytes.isEmpty) {
        logger.warn("Attempted to deserialize null or empty bytes") >>
          none[V].pure[F]
      } else {
        JsonSerializer[F].deserialize[Json](bytes).flatMap {
          case Right(json) =>
            json.as[V] match {
              case Right(v) => v.some.pure[F]
              case Left(err) =>
                logger.warn(s"Failed to decode JSON: ${err.getMessage}") >>
                  none[V].pure[F]
            }
          case Left(err) =>
            logger.warn(s"Failed to deserialize bytes: ${err.getMessage}") >>
              none[V].pure[F]
        }
      }

    override def get[V: Decoder](key: K): F[Option[V]] =
      for {
        hex <- toHex(key)
        entries <- producer.entries
        bytesOpt = entries.get(hex)
        result <- bytesOpt match {
          case Some(bytes) if bytes != null && bytes.nonEmpty =>
            deserializeBytes[V](bytes)
          case Some(_) =>
            logger.warn(s"MptStore.get: Found null/empty bytes for hex=$hex") >>
              none[V].pure[F]
          case None =>
            none[V].pure[F]
        }
      } yield result

    override def getMany[V: Decoder](keys: List[K]): F[Map[K, V]] =
      if (keys.isEmpty) Map.empty[K, V].pure[F]
      else
        for {
          hexKeys <- keys.parTraverse(k => toHex(k).map(k -> _))
          entries <- producer.entries
          results <- hexKeys.traverseFilter {
            case (k, hex) =>
              entries.get(hex) match {
                case Some(bytes) if bytes != null && bytes.nonEmpty =>
                  deserializeBytes[V](bytes).map(_.map(k -> _))
                case Some(_) =>
                  logger.warn(s"MptStore.getMany: Found null/empty bytes for hex=$hex") >>
                    none[(K, V)].pure[F]
                case None =>
                  none[(K, V)].pure[F]
              }
          }
        } yield results.toMap

    override def insert[V: Encoder](key: K, value: V): F[Unit] =
      for {
        hex <- toHex(key)
        bytes <- JsonSerializer[F].serialize(value)
        _ <- producer.insertBytes(Map(hex -> bytes)).void
      } yield ()

    override def insert[V: Encoder](data: Map[K, V]): F[Unit] =
      if (data.isEmpty) Async[F].unit
      else
        for {
          entries <- toHexEntries(data)
          _ <- producer.insertBytes(entries).void
        } yield ()

    override def remove(key: K): F[Unit] =
      for {
        hex <- toHex(key)
        _ <- producer.remove(List(hex)).void
      } yield ()

    override def remove(keys: List[K]): F[Unit] =
      if (keys.isEmpty) Async[F].unit
      else
        for {
          hexKeys <- keys.parTraverse(toHex)
          _ <- producer.remove(hexKeys).void
        } yield ()

    override def contains(key: K): F[Boolean] =
      for {
        hex <- toHex(key)
        entries <- producer.entries
      } yield entries.contains(hex)

    override def isEmpty: F[Boolean] =
      producer.entries.map(_.isEmpty)

    override def clear: F[Unit] =
      logger.info("[MptStore] Clearing store") >> producer.clear

    override def build(snapshotOrdinal: SnapshotOrdinal): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
      producer.buildForOrdinal(snapshotOrdinal)

    override def syncFull[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit] =
      withLock {
        if (newState.isEmpty) {
          logger.info("[MptStore] Empty sync, skipping") >>
            clear >> lastSyncedOrdinalRef.set(Some(ordinal))
        } else
          for {
            _ <- logger.info(s"[MptStore] Full sync with ${newState.size} entries")
            _ <- clear
            newEntries <- toHexEntries(newState)
            _ <- producer.insertBytes(newEntries).void
            _ <- persistAsync(ordinal)
            _ <- build(ordinal)
            _ <- lastSyncedOrdinalRef.set(Some(ordinal))
          } yield ()
      }

    override def syncFullIfNeeded[V: Encoder](newState: => F[Map[K, V]], ordinal: SnapshotOrdinal, expectedRoot: Option[Hash]): F[Unit] =
      // Use atomic modify to prevent race condition where two threads both see needsSync=true.
      // Returns (priorLastOrdinal, needsSync) so the caller can log the decision input
      // alongside the resulting branch. This diagnostic targets the per-retry full-build
      // cycle observed during the alpha.83 wedge: if priorLastSynced is consistently None
      // across retries at the same ordinal, something is resetting lastSyncedOrdinalRef
      // between rounds (a savepoint restore on the failed-round path is the prime suspect).
      lastSyncedOrdinalRef.modify { lastOrdinal =>
        val needsSync = lastOrdinal.forall(_ =!= ordinal)
        if (needsSync) {
          // Mark as syncing with this ordinal immediately to prevent concurrent syncs
          (Some(ordinal), (lastOrdinal, true))
        } else {
          (lastOrdinal, (lastOrdinal, false))
        }
      }.flatMap {
        case (priorLastOrdinal, needsSync) =>
          logger.info(
            s"[MptStore.syncFullIfNeeded] ordinal=$ordinal priorLastSynced=$priorLastOrdinal needsSync=$needsSync"
          ) >>
            (if (needsSync) newState.flatMap(syncFull(_, ordinal))
             else
               // Content-aware skip. The ordinal tag says we are already synced here, but an abandoned-round
               // mutation or a savepoint restore can leave the in-memory entry set stale while the tag persists,
               // so a pure ordinal check can build a proposal/proof off divergent state. When the caller knows
               // the expected stateProof root, verify the producer's current root matches before trusting the
               // no-op; on mismatch force a full resync so we never emit a divergent root.
               expectedRoot match {
                 case None =>
                   logger.debug(s"[MptStore] Skipping sync, already synced at ordinal $ordinal")
                 case Some(expected) =>
                   // Build (not getCurrentRootHash) so pending inserts/removes are applied before reading the
                   // root -- getCurrentRootHash returns the last-built trie root and would miss pending mutations,
                   // letting a stale state pass the check. A Left build is treated as a mismatch (force resync).
                   build(ordinal).flatMap { built =>
                     val currentRoot = built.toOption.map(_.rootHash.value)
                     if (currentRoot.contains(expected))
                       logger.debug(s"[MptStore] Skipping sync, already synced at ordinal $ordinal (root verified)")
                     else
                       logger.warn(
                         s"[MptStore] lastSynced tag matches ordinal=$ordinal but current root " +
                           s"${currentRoot.map(_.show.take(8)).getOrElse("none")} != expected " +
                           s"${expected.show.take(8)}; forcing full resync to avoid divergence"
                       ) >> newState.flatMap(syncFull(_, ordinal))
                   }
               })
      }

    override def sync[V: Encoder](updates: Map[K, V], ordinal: SnapshotOrdinal): F[Unit] =
      if (updates.isEmpty) Async[F].unit
      else
        withLock {
          for {
            _ <- logger.debug(s"[MptStore] Incremental sync with ${updates.size} entries at ordinal=$ordinal")
            _ <- insert(updates)
            _ <- persistAsync(ordinal)
            // Build the trie and cache root hash for this ordinal
            // This is critical for validation - without this, getRootHashForOrdinal returns None
            _ <- build(ordinal).void
            _ <- lastSyncedOrdinalRef.set(Some(ordinal))
          } yield ()
        }

    override def update[V: Encoder](toUpsert: Map[K, V], toRemove: Set[K]): F[Unit] =
      withLock {
        for {
          _ <- remove(toRemove.toList)
          _ <- insert(toUpsert)
        } yield ()
      }

    override def underlying: StatefulMerklePatriciaProducer[F] = producer

    override def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
      withLock {
        producer match {
          case p: StatefulWithPersistenceMerklePatriciaProducer[F] =>
            logger.info(s"[MptStore] Deleting above ordinal=$ordinal") >> p.deleteAbove(ordinal)
          case _ =>
            Async[F].unit
        }
      }

    override def savepoint: F[MptStoreSavepoint[F]] =
      withLock {
        for {
          producerSP <- producer.savepoint
          savedOrdinal <- lastSyncedOrdinalRef.get
        } yield
          new MptStoreSavepoint[F] {
            def restore: F[Unit] =
              withLock(producerSP.restore >> lastSyncedOrdinalRef.set(savedOrdinal))
          }
      }
  }
}
