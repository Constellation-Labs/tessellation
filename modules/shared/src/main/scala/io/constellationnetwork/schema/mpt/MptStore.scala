package io.constellationnetwork.schema.mpt

import cats.Parallel
import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.MerklePatriciaTrie
import io.constellationnetwork.security.mpt.producer._

import io.circe.syntax._
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
  def clear: F[Unit]
  def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]]
  def sync[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit]
  def syncFull[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit]
  def update[V: Encoder](toUpsert: Map[K, V], toRemove: Set[K]): F[Unit]
  def underlying: StatefulMerklePatriciaProducer[F]
  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit]
}

object MptStore {

  def make[F[_]: Async: Parallel: Hasher, K](
    producer: StatefulMerklePatriciaProducer[F],
    toHex: K => F[Hex]
  ): MptStore[F, K] =
    new Impl[F, K](producer, toHex)

  private final class Impl[F[_]: Async: Parallel: Hasher, K](
    producer: StatefulMerklePatriciaProducer[F],
    toHex: K => F[Hex]
  ) extends MptStore[F, K] {

    private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    private def persistAndCutoffAsync(ordinal: SnapshotOrdinal): F[Unit] =
      producer match {
        case p: StatefulWithPersistenceMerklePatriciaProducer[F @unchecked] =>
          for {
            _ <- Async[F].start {
              val persistTask = for {
                _ <- p.persist(ordinal)
                cutoffStart <- Async[F].realTime
                _ <- p.applyCutoff(ordinal)
              } yield ()

              persistTask
            }
          } yield ()
        case _ =>
          ().pure
      }

    private def tryLoadFromDisk(ordinal: SnapshotOrdinal): F[Boolean] =
      producer match {
        case p: FileSystemMerklePatriciaProducer[F @unchecked] =>
          for {
            loaded <- p.load(ordinal)
            _ <-
              if (loaded)
                logger.info(s"Successfully loaded trie from disk for ordinal=$ordinal")
              else
                logger.info(s"No persisted trie found for ordinal=$ordinal")
          } yield loaded
        case _ =>
          logger.debug("Producer does not support loading from disk") >>
            false.pure[F]
      }

    private def toHexEntries[V: Encoder](data: Map[K, V]): F[Map[Hex, Json]] =
      if (data.isEmpty) Async[F].pure(Map.empty[Hex, Json])
      else if (data.size <= 5000) {
        data.toList.parTraverse { case (k, v) => toHex(k).map(_ -> v.asJson) }.map(_.toMap)
      } else {
        val BatchSize = 5000
        for {
          result <- data.toList
            .grouped(BatchSize)
            .toList
            .foldLeftM(Map.empty[Hex, Json]) { (acc, batch) =>
              for {
                batchResult <- batch.parTraverse { case (k, v) => toHex(k).map(_ -> v.asJson) }
                _ <- Async[F].cede
              } yield acc ++ batchResult.toMap
            }
        } yield result
      }

    override def get[V: Decoder](key: K): F[Option[V]] =
      for {
        hex <- toHex(key)
        entries <- producer.entries
      } yield entries.get(hex).flatMap(_.as[V].toOption)

    override def getMany[V: Decoder](keys: List[K]): F[Map[K, V]] =
      if (keys.isEmpty) Async[F].pure(Map.empty)
      else
        for {
          hexKeys <- keys.parTraverse(k => toHex(k).map(k -> _))
          entries <- producer.entries
          results = hexKeys.flatMap {
            case (k, hex) =>
              entries.get(hex).flatMap(_.as[V].toOption).map(k -> _)
          }.toMap
        } yield results

    override def insert[V: Encoder](key: K, value: V): F[Unit] =
      toHex(key).flatMap(hex => producer.insert(Map(hex -> value.asJson)).void)

    override def insert[V: Encoder](data: Map[K, V]): F[Unit] =
      if (data.isEmpty) Async[F].unit
      else toHexEntries(data).flatMap(kvs => producer.insert(kvs).void)

    override def remove(key: K): F[Unit] =
      toHex(key).flatMap(hex => producer.remove(List(hex)).void)

    override def remove(keys: List[K]): F[Unit] =
      if (keys.isEmpty) Async[F].unit
      else keys.parTraverse(toHex).flatMap(hexKeys => producer.remove(hexKeys).void)

    override def contains(key: K): F[Boolean] =
      for {
        hex <- toHex(key)
        entries <- producer.entries
      } yield entries.contains(hex)

    override def clear: F[Unit] =
      logger.info("Clearing MPT store") >> producer.clear

    override def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
      for {
        result <- producer.build
      } yield result

    override def syncFull[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit] =
      if (newState.isEmpty) {
        producer.clear
      } else {
        for {
          _ <- logger.info(s"No persisted trie found, performing full build...")

          newEntries <- toHexEntries(newState)
          _ <- producer.clear
          _ <- producer.insert(newEntries).void
          _ <- persistAndCutoffAsync(ordinal)
          _ <- build
        } yield ()
      }

    override def sync[V: Encoder](updates: Map[K, V], ordinal: SnapshotOrdinal): F[Unit] =
      if (updates.isEmpty) {
        ().pure
      } else {
        for {
          newEntries <- toHexEntries(updates)
          _ <- if (newEntries.nonEmpty) producer.insert(newEntries).void else Async[F].unit
          _ <- persistAndCutoffAsync(ordinal)
        } yield ()
      }

    override def update[V: Encoder](toUpsert: Map[K, V], toRemove: Set[K]): F[Unit] =
      for {
        upsertHex <- if (toUpsert.isEmpty) Async[F].pure(Map.empty[Hex, Json]) else toHexEntries(toUpsert)
        removeHex <- if (toRemove.isEmpty) Async[F].pure(List.empty[Hex]) else toRemove.toList.parTraverse(toHex)
        _ <- if (removeHex.nonEmpty) producer.remove(removeHex).void else Async[F].unit
        _ <- if (upsertHex.nonEmpty) producer.insert(upsertHex).void else Async[F].unit
      } yield ()

    override def underlying: StatefulMerklePatriciaProducer[F] = producer

    override def deleteAbove(ordinal: SnapshotOrdinal): F[Unit] =
      producer match {
        case p: StatefulWithPersistenceMerklePatriciaProducer[F @unchecked] =>
          logger.info(s"Deleting above ordinal=$ordinal") >>
            p.deleteAbove(ordinal)
        case _ =>
          Async[F].unit
      }
  }
}
