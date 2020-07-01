package org.tessellation.schema

import higherkindness.droste.{CVAlgebra, Coalgebra}

trait Edge {
  def sign[A](data: A) = this
}

trait HyperEdge extends Edge {
  val fibers: Seq[Edge]
}

case object Signature extends Edge

case object EdgeData extends Edge

case class EdgeBundle(fibers: Seq[Edge]) extends HyperEdge

case class Transaction(override val data: Edge) extends Fiber[Edge] with Edge {
  override def unit: Hom[Edge] = this
  def endo(x: Hom[_])(transformation: Hom[_]=> Hom[_]): Hom[_] = transformation(x)
  override def tensor(x: Hom[_], y: Hom[_]): Hom[Edge] = Transaction(data.sign(Seq(x, y)))

  override val coalgebra: Coalgebra[Hom, Edge] = Coalgebra {
    case h: Hom[_] => Transaction(this.data.sign(h.data))
  }

  override val algebra: CVAlgebra[Hom, Edge] = CVAlgebra {
    case h: Hom[_] => Transaction(this.data.sign(h.data))
  }

  def newEdge(baseData: Edge) = tensor(this, Transaction(baseData))

}

case class Block(override val data: EdgeBundle) extends Bundle[EdgeBundle](data) with Edge {
  override def tensor(x: Hom[_], y: Hom[_]): Hom[EdgeBundle] = Block(EdgeBundle(Seq(this.sign(x.data), this.sign(x.data))))

  def endo(x: Hom[_])(transformation: Hom[_] => Hom[_]): Hom[_] = transformation(x)

  override val coalgebra: Coalgebra[Hom, EdgeBundle] = Coalgebra {
    case t: Hom[_] => Block(EdgeBundle(this.data.fibers))
  }

  override val algebra: CVAlgebra[Hom, EdgeBundle] = CVAlgebra {
    case t: Hom[_] => EdgeBundle(this.data.fibers)
  }

  def combine(x: Block, y: Block): Block = Block(EdgeBundle(x.data.fibers ++ y.data.fibers))

}

case class Snapshot(convergedState: Seq[Block]) extends Simplex[EdgeBundle](convergedState) with Edge {
  override val coalgebra: Coalgebra[Hom, EdgeBundle] = Coalgebra {
    case t: Hom[_] => Block(this.convergedState.head.data)
  }

  override val algebra: CVAlgebra[Hom, EdgeBundle] = CVAlgebra {
    case t: Hom[_] => this.convergedState.head.data
  }
  override val data: EdgeBundle = this.convergedState.head.data //todo reduce over sign operation, get EdgeBundle

  override def tensor(x: Hom[_], y: Hom[_]): Hom[EdgeBundle] = Block(EdgeBundle(Seq(this.sign(x.data), this.sign(x.data))))

  def endo(x: Hom[_])(transformation: Hom[_] => Hom[_]): Hom[_] = transformation(x)

  def combine(x: Snapshot, y: Snapshot): Snapshot = Snapshot(x.convergedState ++ x.convergedState)

}

object ChannelApp extends App {
  val channel = Cell[Transaction](Transaction(Signature))
}