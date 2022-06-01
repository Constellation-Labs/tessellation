package org.tessellation.dag.domain.block

import cats.data.NonEmptyList
import cats.syntax.reducible._

import scala.collection.immutable.SortedSet

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.Fiber
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

case class DAGBlockReference(parents: NonEmptyList[BlockReference])
case class DAGBlockData(transactions: Set[Signed[Transaction]])

@derive(encoder, decoder, order, show)
case class DAGBlock(
  parent: NonEmptyList[BlockReference],
  transactions: SortedSet[Signed[Transaction]]
) extends Fiber[DAGBlockReference, DAGBlockData] {
  val height: Height = parent.maximum.height.next

  def reference = DAGBlockReference(parent)
  def data = DAGBlockData(transactions)
}

object DAGBlock {
  implicit object OrderingInstance extends OrderBasedOrdering[DAGBlock]
}
