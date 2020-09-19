package org.tessellation

import cats.effect.{ExitCode, IOApp}
import cats.implicits._
import org.tessellation.schema.{Cell, Cell2, Cocell, Context, Hom, Topos, Ω}
import org.tessellation.schema.Hom._
import cats.implicits._
import higherkindness.droste.{Algebra, CVAlgebra, Coalgebra, GAlgebra, GCoalgebra, Gather, RAlgebra, RCoalgebra, Scatter, scheme}
import higherkindness.droste.data.{:<, Attr}
import higherkindness.mu.rpc.protocol._
import org.tessellation.ConsensusExample.{intGather, intScatter}
import org.tessellation.StreamExample.pipeline
import org.tessellation.hypergraph.EdgePartitioner
import org.tessellation.hypergraph.EdgePartitioner.EdgePartitioner

object Main extends App {
  println("Welcome to " |+| "Tessellation!")
  LiftExample
}

object LiftExample extends App {
  println("Lift " |+| "Example!")

  val intCell: Hom[Int, Int] = Cell[Int, Int](0)
  val intTopos: Hom[Int, Int] = Cell2[Int, Int](2, 3)
  val constellation = intCell >>> intTopos

  val intToposClone = Cell2[Int, Int](0, 1)
  val myClone = constellation >>> intToposClone
}

object StreamExample extends App {
  import fs2.Stream
  import cats.effect.IO

  def intCell(i: Int): Hom[Int, Int] = Cell[Int, Int](i)
  def chainEffects(operad: Hom[Int, Int])(i: Int): Hom[Int, Int] = operad >>> intCell(i)
  def pipeline(i: Int): Stream[Hom[Int, *], Int] = Stream.eval[Hom[Int, *], Int] (chainEffects(Context())(i))

  val dummyStream = Stream(1,2,3)
  val effectfulStream = dummyStream.flatMap(pipeline)
}

import higherkindness.droste.data.{:<, Coattr}
import higherkindness.droste.syntax.compose._
object ConsensusExample extends App {
  import fs2.Stream
  import cats.effect.IO

  val submitResult: RCoalgebra[Int, Hom[Int, *], Int] = RCoalgebra {
    case int: Int => Cell(int)
  }

  val cellCoalgebra: Coalgebra[Hom[Int, *], Int] = Coalgebra[Hom[Int, *], Int] { thing: Int => Cell(thing) }

  val fromCellHelper: Algebra[Hom[Int, *], Int] = Algebra {
    case Cell(n) => n + 1
    case Cell2(a, b)    => a + b
  }

  // note: n is the fromCellHelper helper value from the previous level of recursion
  val proposalRingAlgebra: RAlgebra[Int, Hom[Int, *], Int] = RAlgebra {
    case Cell2(a, (n, value)) => value + (n + 1) * (n + 1)
    case Cell(a) => a
  }

  val combineProposals: CVAlgebra[Hom[Int, *], Int] = CVAlgebra {
    case Cell2(a, r1 :< Cell(r2)) => a + r2
    case cell@Cell(aa) => aa
  }

  val intGather: GAlgebra.Gathered[Hom[Int, *], Attr[Hom[Int, *], Int], Int] = combineProposals.gather(Gather.histo)


  val intScatter: GCoalgebra.Scattered[Hom[Int, *], Int, Either[Int, Int]] = submitResult.scatter(Scatter.gapo(cellCoalgebra))


  val takeHighestIntegerConsensus: Int => Int = scheme.ghylo(
    intGather,
    intScatter)
  //todo note we can just use Cell istead of new class I just ran out of time to make new constructor

  case class MyNewCell[A, B](override val a: A) extends Topos[A, B] {
    val takeHighestIntegerConsensus: Int => Int = scheme.ghylo(
      intGather,
      intScatter)
  }

  def intCell(i: Int): Hom[Int, Int] = MyNewCell[Int, Int](i)
  def chainEffects(operad: Hom[Int, Int])(i: Int): Hom[Int, Int] = operad >>> intCell(i)
  def pipeline(i: Int): Stream[Hom[Int, *], Int] = Stream.eval[Hom[Int, *], Int] (chainEffects(Context())(i))

  val dummyStream = Stream(1,2,3)
  val effectfulStream = dummyStream.flatMap(pipeline)
}
