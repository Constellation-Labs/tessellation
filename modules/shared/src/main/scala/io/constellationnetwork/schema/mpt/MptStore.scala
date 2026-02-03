package io.constellationnetwork.schema.mpt

import cats.Parallel
import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.producer._

import io.circe.{Decoder, Encoder, Json}
import org.typelevel.log4cats.slf4j.Slf4jLogger

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
  def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]]
  def sync[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit]
  def syncFull[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit]
  def syncFullIfNeeded[V: Encoder](newState: => F[Map[K, V]], ordinal: SnapshotOrdinal): F[Unit]
  def update[V: Encoder](toUpsert: Map[K, V], toRemove: Set[K]): F[Unit]
  def underlying: StatefulMerklePatriciaProducer[F]
  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit]

  /** Returns the ordinal at which this store was last synced. Used for optimistic validation — if the store is at the target ordinal, we
    * can use the producer directly instead of rebuilding from state.
    */
  def currentOrdinal: F[Option[SnapshotOrdinal]]
}

object MptStore {

  def make[F[_]: Async: Parallel: Hasher: JsonSerializer, K](
    producer: StatefulMerklePatriciaProducer[F],
    toHex: K => F[Hex]
  ): F[MptStore[F, K]] =
    Ref.of[F, Option[SnapshotOrdinal]](None).map { lastSyncedOrdinalRef =>
      new Impl[F, K](producer, toHex, lastSyncedOrdinalRef): MptStore[F, K]
    }

  private final class Impl[F[_]: Async: Parallel: Hasher: JsonSerializer, K](
    producer: StatefulMerklePatriciaProducer[F],
    toHex: K => F[Hex],
    lastSyncedOrdinalRef: Ref[F, Option[SnapshotOrdinal]]
  ) extends MptStore[F, K] {

    private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)
    private val BatchSize = 5000

    private def persistAsync(ordinal: SnapshotOrdinal): F[Unit] =
      producer match {
        case p: StatefulWithPersistenceMerklePatriciaProducer[F] =>
          Async[F].start(p.persist(ordinal)).void
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

    override def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
      producer.build

    override def syncFull[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit] =
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
          _ <- build
          _ <- lastSyncedOrdinalRef.set(Some(ordinal))
        } yield ()

    override def syncFullIfNeeded[V: Encoder](newState: => F[Map[K, V]], ordinal: SnapshotOrdinal): F[Unit] =
      lastSyncedOrdinalRef.get.flatMap { lastOrdinal =>
        val needsSync = lastOrdinal.forall(_ =!= ordinal)
        if (needsSync)
          newState.flatMap(syncFull(_, ordinal))
        else
          logger.debug(s"[MptStore] Skipping sync, already synced at ordinal $ordinal")
      }

    override def sync[V: Encoder](updates: Map[K, V], ordinal: SnapshotOrdinal): F[Unit] =
      if (updates.isEmpty) Async[F].unit
      else
        for {
          _ <- logger.debug(s"[MptStore] Incremental sync with ${updates.size} entries")
          _ <- insert(updates)
          _ <- persistAsync(ordinal)
        } yield ()

    override def update[V: Encoder](toUpsert: Map[K, V], toRemove: Set[K]): F[Unit] =
      for {
        _ <- remove(toRemove.toList)
        _ <- insert(toUpsert)
      } yield ()

    override def underlying: StatefulMerklePatriciaProducer[F] = producer

    override def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
      producer match {
        case p: StatefulWithPersistenceMerklePatriciaProducer[F] =>
          logger.info(s"[MptStore] Deleting above ordinal=$ordinal") >> p.deleteAbove(ordinal)
        case _ =>
          Async[F].unit
      }

    override def currentOrdinal: F[Option[SnapshotOrdinal]] =
      lastSyncedOrdinalRef.get
  }
}
