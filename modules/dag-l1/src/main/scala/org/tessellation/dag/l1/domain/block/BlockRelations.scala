package org.tessellation.dag.l1.domain.block

import cats.effect.kernel.Async
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.BlockReference
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

object BlockRelations {

  def dependsOn[F[_]: Async: KryoSerializer](
    blocks: Hashed[DAGBlock]
  )(block: Signed[DAGBlock]): F[Boolean] = dependsOn(Set(blocks))(block)

  def dependsOn[F[_]: Async: KryoSerializer](
    blocks: Set[Hashed[DAGBlock]],
    references: Set[BlockReference] = Set.empty
  )(block: Signed[DAGBlock]): F[Boolean] = {
    def dstAddresses = blocks.flatMap(_.transactions.toSortedSet.toList.map(_.value.destination))

    def isChild =
      block.parent.exists(parentRef => (blocks.map(_.ownReference) ++ references).exists(_ === parentRef))
    def hasReferencedAddress = block.transactions.map(_.source).exists(srcAddress => dstAddresses.exists(_ === srcAddress))
    def hasReferencedTx = blocks.toList
      .flatTraverse(_.transactions.toSortedSet.toList.traverse(TransactionReference.of(_)))
      .map(_.toSet)
      .map { txRefs =>
        block.transactions.map(_.parent).exists(txnParentRef => txRefs.exists(_ === txnParentRef))
      }

    if (isChild || hasReferencedAddress) true.pure[F] else hasReferencedTx
  }
}
