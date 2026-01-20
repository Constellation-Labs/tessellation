package io.constellationnetwork.security.mpt.producer

import cats.Parallel
import cats.effect.Async
import cats.syntax.functor._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.MerklePatriciaTrie
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver

import fs2.Stream
import io.circe.{Encoder, Json}

trait MerklePatriciaProducer[F[_]] {
  def create[A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrie]

  def insert[A: Encoder](
    current: MerklePatriciaTrie,
    data: Map[Hex, A]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]]

  def remove(
    current: MerklePatriciaTrie,
    keys: List[Hex]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]]

  def getProver(trie: MerklePatriciaTrie): F[MerklePatriciaSingleInclusionProver[F]]
}

trait StatefulMerklePatriciaProducer[F[_]] {
  def entries: F[Map[Hex, Array[Byte]]]
  def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]]
  def insert[A: Encoder](data: Map[Hex, A]): F[Either[MerklePatriciaError, Unit]]
  def insertBytes(data: Map[Hex, Array[Byte]]): F[Either[MerklePatriciaError, Unit]]
  def update[A: Encoder](key: Hex, value: A): F[Either[MerklePatriciaError, Unit]]
  def remove(keys: List[Hex]): F[Either[MerklePatriciaError, Unit]]
  def clear: F[Unit]
  def getProver: F[MerklePatriciaSingleInclusionProver[F]]
  def buildHexMap(data: Map[GlobalStateKey, Json]): F[Map[Hex, Array[Byte]]]
}

trait StatefulWithPersistenceMerklePatriciaProducer[F[_]] extends StatefulMerklePatriciaProducer[F] {
  def persist(ordinal: SnapshotOrdinal): F[Unit]
  def load(ordinal: SnapshotOrdinal): F[Boolean]
  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit]
  def listStoredOrdinals: F[Stream[F, SnapshotOrdinal]]
  def applyCutoff(ordinal: SnapshotOrdinal): F[Unit]
}

object MerklePatriciaProducer {
  def apply[F[_]](implicit producer: MerklePatriciaProducer[F]): MerklePatriciaProducer[F] = producer

  def make[F[_]: Hasher: Async]: MerklePatriciaProducer[F] = stateless[F]

  def stateless[F[_]: Hasher: Async]: MerklePatriciaProducer[F] =
    new StatelessMerklePatriciaProducer[F]

  def parallel[F[_]: Hasher: Async: Parallel: JsonSerializer]: MerklePatriciaProducer[F] =
    new ParallelMerklePatriciaProducer[F]

  def inMemory[F[_]: Async: Hasher: Parallel: JsonSerializer](
    initial: Map[Hex, Array[Byte]] = Map.empty
  ): F[StatefulMerklePatriciaProducer[F]] =
    InMemoryMerklePatriciaProducer.make[F](initial).widen[StatefulMerklePatriciaProducer[F]]
}

sealed trait MerklePatriciaError extends Throwable
case class InvalidData(message: String) extends MerklePatriciaError
case class OperationError(message: String) extends MerklePatriciaError
