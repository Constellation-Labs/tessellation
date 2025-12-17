package io.constellationnetwork.schema.mpt

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.MerklePatriciaTrie
import io.constellationnetwork.security.mpt.producer.{MerklePatriciaError, StatefulMerklePatriciaProducer}

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

  def sync[V: Encoder](newState: Map[K, V]): F[Unit]

  def syncFull[V: Encoder](newState: Map[K, V]): F[Unit]

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
      else
        data.toList.parTraverse { case (k, v) => toHex(k).map(_ -> v.asJson) }
          .flatMap(kvs => producer.insert(kvs.toMap).void)

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

    override def syncFull[V: Encoder](newState: Map[K, V]): F[Unit] =
      if (newState.isEmpty) producer.clear
      else
        for {
          newEntries <- newState.toList.parTraverse { case (k, v) => toHex(k).map(_ -> v.asJson) }
            .map(_.toMap)

          currentEntries <- producer.entries

          keysToRemove = currentEntries.keySet -- newEntries.keySet
          keysToUpsert = newEntries.filter {
            case (k, vJson) =>
              !currentEntries.get(k).contains(vJson)
          }

          _ <- if (keysToRemove.nonEmpty) producer.remove(keysToRemove.toList).void else Async[F].unit
          _ <- if (keysToUpsert.nonEmpty) producer.insert(keysToUpsert).void else Async[F].unit
        } yield ()

    override def sync[V: Encoder](updates: Map[K, V]): F[Unit] =
      if (updates.isEmpty) Async[F].unit
      else
        for {
          newEntries <- updates.toList.parTraverse {
            case (k, v) =>
              toHex(k).map(_ -> v.asJson)
          }.map(_.toMap)

          currentEntries <- producer.entries

          keysToUpsert = newEntries.filter {
            case (k, v) =>
              !currentEntries.get(k).contains(v)
          }

          _ <- if (keysToUpsert.nonEmpty) producer.insert(keysToUpsert) else Async[F].unit
        } yield ()

    override def update[V: Encoder](toUpsert: Map[K, V], toRemove: Set[K]): F[Unit] =
      for {
        upsertHex <-
          if (toUpsert.isEmpty) Async[F].pure(Map.empty[Hex, Json])
          else
            toUpsert.toList.parTraverse { case (k, v) => toHex(k).map(_ -> v.asJson) }
              .map(_.toMap)

        removeHex <-
          if (toRemove.isEmpty) Async[F].pure(List.empty[Hex])
          else toRemove.toList.parTraverse(toHex)

        _ <- if (removeHex.nonEmpty) producer.remove(removeHex).void else Async[F].unit
        _ <- if (upsertHex.nonEmpty) producer.insert(upsertHex).void else Async[F].unit
      } yield ()

    override def underlying: StatefulMerklePatriciaProducer[F] = producer
  }
}
