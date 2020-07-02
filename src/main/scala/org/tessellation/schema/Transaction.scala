package org.tessellation.schema

import cats.Monoid

trait Edge {
  def sign[A](data: A) = this
}

trait HyperEdge extends Edge {
  val fibers: Seq[Edge]
}

// `i: Int` is temporary to distinguish instances
case class Signature(i: Int) extends Edge

case object EdgeData extends Edge

case class EdgeBundle(fibers: Seq[Edge]) extends HyperEdge

case class Transaction(data: Edge) extends Fiber[Edge, Edge] with Edge {
  override def unit: Hom[Edge, Edge] = this

  def newEdge(baseData: Edge) = tensor(this, Transaction(baseData))

}

case class Block(val data: EdgeBundle) extends Bundle[EdgeBundle, EdgeBundle](data) with Edge

object Block {
  def apply(txs: List[Transaction]): Block = new Block(EdgeBundle(txs))

  implicit val blockMonoidInstance: Monoid[Block] = new Monoid[Block] {
    override def empty: Block = Block.apply(List.empty)

    override def combine(x: Block, y: Block): Block = Block(EdgeBundle(y.data.fibers ++ x.data.fibers))
  }
}

case class Snapshot[A, B, C](convergedState: Seq[Fiber[A, B]]) extends Simplex[A, B, C](convergedState) with Edge {
  def combine(x: Snapshot[A, B, _], y: Snapshot[_, B, C]): Snapshot[A, B, C] = Snapshot(x.convergedState ++ x.convergedState)
}


object ChannelApp extends App {
  val channel = Cell[Transaction, Transaction](Transaction(Signature(1)))
}