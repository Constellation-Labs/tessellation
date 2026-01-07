package io.constellationnetwork.schema.mpt

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.MerklePatriciaTrie
import io.constellationnetwork.security.mpt.producer._

import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}

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

    private def persistAndCutoff(ordinal: SnapshotOrdinal): F[Unit] =
      producer match {
        case p: StatefulWithPersistenceMerklePatriciaProducer[F] =>
          p.persist(ordinal) >> p.applyCutoff(ordinal)
        case _ =>
          Async[F].unit
      }

    private def toHexEntries[V: Encoder](data: Map[K, V]): F[Map[Hex, Json]] =
      data.toList.parTraverse { case (k, v) => toHex(k).map(_ -> v.asJson) }.map(_.toMap)

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
      producer.clear

    override def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
      producer.build

    override def syncFull[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit] =
      if (newState.isEmpty) producer.clear
      else
        for {
          newEntries <- toHexEntries(newState)
          currentEntries <- producer.entries
          keysToRemove = currentEntries.keySet -- newEntries.keySet
          keysToUpsert = newEntries.filterNot { case (k, v) => currentEntries.get(k).contains(v) }
          _ <- if (keysToRemove.nonEmpty) producer.remove(keysToRemove.toList).void else Async[F].unit
          _ <- if (keysToUpsert.nonEmpty) producer.insert(keysToUpsert).void else Async[F].unit
          _ <- persistAndCutoff(ordinal)
        } yield ()

    override def sync[V: Encoder](updates: Map[K, V], ordinal: SnapshotOrdinal): F[Unit] =
      if (updates.isEmpty) Async[F].unit
      else
        for {
          newEntries <- toHexEntries(updates)
          currentEntries <- producer.entries
          keysToUpsert = newEntries.filterNot { case (k, v) => currentEntries.get(k).contains(v) }
          _ <- if (keysToUpsert.nonEmpty) producer.insert(keysToUpsert).void else Async[F].unit
          _ <- persistAndCutoff(ordinal)
        } yield ()

    override def update[V: Encoder](toUpsert: Map[K, V], toRemove: Set[K]): F[Unit] =
      for {
        upsertHex <- if (toUpsert.isEmpty) Async[F].pure(Map.empty[Hex, Json]) else toHexEntries(toUpsert)
        removeHex <- if (toRemove.isEmpty) Async[F].pure(List.empty[Hex]) else toRemove.toList.parTraverse(toHex)
        _ <- if (removeHex.nonEmpty) producer.remove(removeHex).void else Async[F].unit
        _ <- if (upsertHex.nonEmpty) producer.insert(upsertHex).void else Async[F].unit
      } yield ()

    override def underlying: StatefulMerklePatriciaProducer[F] = producer
  }
}
