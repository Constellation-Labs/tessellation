package org.tessellation.schema.block

import cats.data.{NonEmptyList, NonEmptySet}

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.codecs.NonEmptySetCodec
import org.tessellation.schema.Block.BlockConstructor
import org.tessellation.schema._
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.Decoder

@derive(show, eqv, encoder, decoder, order)
case class DAGBlock(
  parent: NonEmptyList[BlockReference],
  transactions: NonEmptySet[Signed[Transaction]]
) extends Block {}

object DAGBlock {
  implicit object OrderingInstance extends OrderBasedOrdering[DAGBlock]

  implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[Transaction]]] =
    NonEmptySetCodec.decoder[Signed[Transaction]]

  implicit object OrderingInstanceAsActiveTip extends OrderBasedOrdering[BlockAsActiveTip[DAGBlock]]

  implicit val constructor = new BlockConstructor[DAGBlock] {
    def create(parents: NonEmptyList[BlockReference], transactions: NonEmptySet[Signed[Transaction]]): DAGBlock =
      DAGBlock(parents, transactions)
  }
}
