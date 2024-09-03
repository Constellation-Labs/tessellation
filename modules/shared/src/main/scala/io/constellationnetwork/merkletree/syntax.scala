package io.constellationnetwork.merkletree

import cats.data.NonEmptyList
import cats.effect.kernel.Sync
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.security.Hasher

import io.circe.Encoder

object syntax extends SortedMapOps

trait SortedMapOps {
  implicit class SortedMapOpsImpl[K: Encoder, V: Encoder](a: SortedMap[K, V]) {
    def merkleTree[F[_]: Sync: Hasher]: F[Option[MerkleTree]] =
      a.toList
        .traverse(_.hash)
        .map(NonEmptyList.fromList)
        .map(_.map(MerkleTree.from))
  }
}
