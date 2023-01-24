package org.tessellation.dag.domain.block

import cats.data.{NonEmptyList, NonEmptySet}

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.codecs.NonEmptySetCodec
import org.tessellation.schema._
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Decoder

case class DAGBlockReference(parents: NonEmptyList[BlockReference]) extends BaseBlockReference
case class DAGBlockData(transactions: NonEmptySet[Signed[Transaction]]) extends BaseBlockData[Transaction]
@derive(order, show, encoder, decoder)
case class DAGBlockAsActiveTip(block: Signed[DAGBlock], usageCount: NonNegLong) extends BlockAsActiveTip[DAGBlock]

object DAGBlockAsActiveTip {
  implicit object OrderingInstance extends OrderBasedOrdering[DAGBlockAsActiveTip]
}

@derive(show, eqv, encoder, decoder, order)
case class DAGBlock(
  parent: NonEmptyList[BlockReference],
  transactions: NonEmptySet[Signed[Transaction]]
) extends Block[Transaction] {
  def reference: BaseBlockReference = DAGBlockReference(parent)
  def data: BaseBlockData[Transaction] = DAGBlockData(transactions)
}

object DAGBlock {
  implicit object OrderingInstance extends OrderBasedOrdering[DAGBlock]

  implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[Transaction]]] =
    NonEmptySetCodec.decoder[Signed[Transaction]]

  implicit class HashedOps(hashedBlock: Hashed[DAGBlock]) {
    def ownReference = BlockReference(hashedBlock.height, hashedBlock.proofsHash)
  }
}
