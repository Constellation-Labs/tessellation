package org.tessellation

import cats.effect.{ExitCode, IOApp}
import org.tessellation.schema.{Cell, Cell2, Cocell, Context, Hom, Topos, Ω}
import org.tessellation.schema.Hom._
import fs2.Stream
import cats.syntax.all._
import higherkindness.droste.{Algebra, CVAlgebra, Coalgebra, GAlgebra, GCoalgebra, Gather, RAlgebra, RCoalgebra, Scatter, scheme}
import higherkindness.droste.data.{:<, Attr}
import org.tessellation.ConsensusExample.{intGather, intScatter}
import org.tessellation.StreamExample.pipeline
import org.tessellation.hypergraph.EdgePartitioner
import org.tessellation.hypergraph.EdgePartitioner.EdgePartitioner
import org.tessellation.serialization.{Kryo, KryoRegistrar, SerDe}

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

/**
 * Serialization use-case example
 * Remember to remove registration entry in `KryoRegistrar` when removing this example
 */
object SerializationExample extends App {
  import cats.syntax.all._
  /**
   * Instantiate the de/serialization service.
   * Use `SerDe` as a dependency trait.
   */
  val registrar = new KryoRegistrar()
  val ser: SerDe = Kryo(registrar)

  def roundtrip[T <: Any](obj: T) = {
    val result = for {
      serialized <- ser.serialize(obj).leftMap(e => s"Serialization error: ${e.reason.getMessage}")
      _ = println(s"Serialized ${obj} is ${serialized}")
      deserialized <- ser.deserialize[T](serialized).leftMap(e => s"Deserialization error: ${e.reason.getMessage}")
      _ = println(s"Deserialized is ${deserialized}")
    } yield ()

    result match {
      case Left(a) => println(a)
      case _ => ()
    }
  }


  case class Lorem(a: String)

  roundtrip(2L)
  roundtrip(Lorem("ipsum"))

  case class UnknownLorem(a: Int)
  roundtrip(UnknownLorem(4))
}