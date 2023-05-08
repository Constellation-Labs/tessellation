package org.tessellation.merkletree

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer

object syntax extends SortedMapOps

trait SortedMapOps {
  implicit class SortedMapOpsImpl[K, V](a: SortedMap[K, V]) {
    def merkleTree[F[_]: MonadThrow: KryoSerializer]: F[Option[MerkleTree]] =
      a.toList
        .traverse(_.hashF)
        .map(NonEmptyList.fromList)
        .map(_.map(MerkleTree.from))
  }
}
