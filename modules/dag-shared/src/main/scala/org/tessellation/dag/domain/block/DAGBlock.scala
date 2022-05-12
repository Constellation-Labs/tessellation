package org.tessellation.dag.domain.block

import cats.data.NonEmptyList
import cats.syntax.contravariant._
import cats.syntax.reducible._
import cats.syntax.semigroup._
import cats.{Monoid, Order}

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.Fiber
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

case class DAGBlockReference(parents: NonEmptyList[BlockReference])
case class DAGBlockData(transactions: Set[Signed[Transaction]])

@derive(encoder, decoder, eqv, show)
case class DAGBlock(
  parent: NonEmptyList[BlockReference],
  transactions: Set[Signed[Transaction]]
) extends Fiber[DAGBlockReference, DAGBlockData] {
  val height: Height = parent.maximum.height.next

  def reference = DAGBlockReference(parent)
  def data = DAGBlockData(transactions)
}

object DAGBlock {
  private implicit val orderMonoid: Monoid[Order[DAGBlock]] = Order.whenEqualMonoid[DAGBlock]

  implicit val order: Order[DAGBlock] =
    Order[Height].contramap((b: DAGBlock) => b.height) |+|
      Order[NonEmptyList[BlockReference]].contramap((b: DAGBlock) => b.parent) |+|
      Order[List[Signed[Transaction]]].contramap((b: DAGBlock) => b.transactions.toList.sorted)

}
