package org.tessellation.merkletree

import cats.data.NonEmptyList
import cats.effect.kernel.Sync
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import org.tessellation.ext.crypto._
import org.tessellation.security.Hasher

import io.circe.Encoder

object syntax extends SortedMapOps

trait SortedMapOps {
  implicit class SortedMapOpsImpl[K: Encoder, V: Encoder](a: SortedMap[K, V]) {
    def merkleTree[F[_]: Sync: Hasher]: F[Option[MerkleTree]] =
      a.toList
        .traverse(_.hash)
        .map(NonEmptyList.fromList)
        .map(_.map(MerkleTree.from))

    def compatibleMerkleTree[F[_]: Sync: Hasher]: F[Option[MerkleTree]] =
      a.toList
        .traverse(Hasher[F].hashKryo)
        .map(NonEmptyList.fromList)
        .map(_.map(MerkleTree.from))
  }
}
