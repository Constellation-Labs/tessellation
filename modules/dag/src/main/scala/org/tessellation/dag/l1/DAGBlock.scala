package org.tessellation.dag.l1

import cats.data.NonEmptyList

import org.tessellation.schema.L1Block
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.ops.toCoercibleIdOps

@derive(encoder, decoder)
case class BlockReference(hash: Hash, height: Height)

@derive(encoder, decoder)
case class DAGBlock(transactions: Set[Signed[Transaction]], parent: NonEmptyList[BlockReference]) extends L1Block {

  // TODO: looks ugly
  // TODO: confirm if we should increment the lower or higher parent height value
  val height: Height = Height(parent.sortBy(_.height.value).head.height.coerce + 1L)

}
