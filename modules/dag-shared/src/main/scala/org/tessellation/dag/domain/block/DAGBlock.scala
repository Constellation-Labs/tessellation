package org.tessellation.dag.domain.block

import cats.data.NonEmptySet._
import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.reducible._

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.codecs.NonEmptySetCodec
import org.tessellation.schema.Fiber
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.Decoder

case class DAGBlockReference(parents: NonEmptyList[BlockReference])
case class DAGBlockData(transactions: NonEmptySet[Signed[Transaction]])

@derive(encoder, decoder, order, show)
case class DAGBlock(
  parent: NonEmptyList[BlockReference],
  transactions: NonEmptySet[Signed[Transaction]]
) extends Fiber[DAGBlockReference, DAGBlockData] {
  val height: Height = parent.maximum.height.next

  def reference = DAGBlockReference(parent)
  def data = DAGBlockData(transactions)
}

object DAGBlock {
  implicit object OrderingInstance extends OrderBasedOrdering[DAGBlock]

  implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[Transaction]]] =
    NonEmptySetCodec.decoder[Signed[Transaction]]
}
