package org.tessellation.schema.block

import cats.data.{NonEmptyList, NonEmptySet}

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.codecs.NonEmptySetCodec
import org.tessellation.schema._
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.Decoder

@derive(show, eqv, encoder, decoder, order)
case class DAGBlock(
  parent: NonEmptyList[BlockReference],
  transactions: NonEmptySet[Signed[DAGTransaction]]
) extends Block[DAGTransaction] {
  def reference: ParentBlockReference = ParentBlockReference(parent)
  def data: BlockData[DAGTransaction] = BlockData(transactions)
}

object DAGBlock {

  implicit class HashedOps(hashedBlock: Hashed[DAGBlock]) {
    def ownReference = BlockReference(hashedBlock.height, hashedBlock.proofsHash)
  }
  implicit object OrderingInstance extends OrderBasedOrdering[DAGBlock]

  implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[DAGTransaction]]] =
    NonEmptySetCodec.decoder[Signed[DAGTransaction]]

  implicit object OrderingInstanceAsActiveTip extends OrderBasedOrdering[BlockAsActiveTip[DAGBlock]]
}
