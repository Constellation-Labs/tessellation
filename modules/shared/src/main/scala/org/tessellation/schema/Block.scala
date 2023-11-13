package org.tessellation.schema

import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.reducible._

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.codecs
import org.tessellation.schema._
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.Decoder

case class ParentBlockReference(parents: NonEmptyList[BlockReference])
case class BlockData(transactions: NonEmptySet[Signed[Transaction]])

@derive(show, eqv, encoder, decoder, order)
case class Block(parent: NonEmptyList[BlockReference], transactions: NonEmptySet[Signed[Transaction]])
    extends Fiber[ParentBlockReference, BlockData] {

  val height: Height = parent.maximum.height.next

  def reference: ParentBlockReference = ParentBlockReference(parent)

  def data: BlockData = BlockData(transactions)
}

object Block {
  implicit object OrderingInstance extends OrderBasedOrdering[Block]

  implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[Transaction]]] =
    codecs.NonEmptySetCodec.decoder[Signed[Transaction]]

  implicit class HashedOps(hashedBlock: Hashed[Block]) {
    def ownReference = BlockReference(hashedBlock.height, hashedBlock.proofsHash)
  }
}
