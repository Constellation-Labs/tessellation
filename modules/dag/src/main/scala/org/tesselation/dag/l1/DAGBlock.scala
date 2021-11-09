package org.tesselation.dag.l1

import cats.data.NonEmptyList

import org.tesselation.schema.L1Block
import org.tesselation.schema.height.Height
import org.tesselation.schema.transaction.Transaction
import org.tesselation.security.hash.Hash
import org.tesselation.security.signature.Signed

import io.estatico.newtype.ops.toCoercibleIdOps

case class BlockReference(hash: Hash, height: Height)

case class DAGBlock(transactions: Seq[Signed[Transaction]], parent: NonEmptyList[BlockReference]) extends L1Block {

  // TODO: looks ugly
  // TODO: confirm if we should increment the lower or higher parent height value
  val height: Height = Height(parent.sortBy(_.height.value).head.height.coerce + 1L)

}
