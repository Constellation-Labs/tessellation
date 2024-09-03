package io.constellationnetwork.schema

import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.reducible._

import io.constellationnetwork.ext.cats.data.OrderBasedOrdering
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.ext.codecs
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.schema.transaction.Transaction
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

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
