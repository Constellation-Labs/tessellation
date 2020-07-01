package org.tessellation.schema

import cats.data.State
import cats.effect.{Concurrent, IO}
import cats.free.Free
import cats.implicits._
import cats.{Applicative, Bimonad, Eval, Functor, Monad, MonoidK, Traverse, ~>}
import shapeless.{:+:, CNil, Coproduct, HList, HNil, ProductTypeClass, TypeClass}

import scala.annotation.tailrec
import scala.collection.mutable


case class CellT[F[_] : Concurrent, A](value: A) {}

case class Cell[A](value: A) extends Hom[A] {
  def job: State[Cell[_], A] = State.pure(value)
}

object Cell {
  //todo might want implicit for Traverse[CellT], should work since F is specified
  implicit val cellBimonad = new Bimonad[Cell]{
    def coflatMap[A, B](fa: Cell[A])(f: Cell[A] => B): Cell[B] = Cell(f(fa))

    def flatMap[A, B](fa: Cell[A])(f: A => Cell[B]): Cell[B] = f.apply(fa.value)

    def pure[A](x: A): Cell[A] = Cell(x)

    def extract[A](x: Cell[A]): A = x.value

    @tailrec
    def tailRecM[A, B](a: A)(f: A => Cell[Either[A, B]]): Cell[B] = f(a) match {
      case Cell(either) => either match {
        case Left(a) => tailRecM(a)(f)
        case Right(b) => Cell(b)
      }
    }
  }

  implicit val cellMonoid = new MonoidK[Cell] { //todo use MonoidK mixing State[Cell, A] monads across the Enrichment
    override def empty[A]: Cell[A] = ???

    override def combineK[A](x: Cell[A], y: Cell[A]): Cell[A] = ???
  }
}

//Monad is Enriched/free cofree comonadic, enrichment ensures the Free Traversals in poset ordering, makes it State-full
//category. Thus, we pass Kleisli of State across Top dimension, and Cofree/parallel in co dimension. Trick is to use
//State to handle concurrent orderings at compile time using state.
/*
object EnrichApp extends App {

  import Cell._
  import Enrichment.TopEnrichedTraverse

  val x = List(5, 4, 3, 2, 1)
  var results = new mutable.MutableList[Int]()

  def g(i: Cell[Int]): IO[Unit] = IO {
    Thread.sleep(i.value * 100)
    results = results :+ i.value
    println(i.value)
  }

  val loadCellMonad = Hom.traverseInstance
  //Note: we want topologicalTraverse for Stateful (Ordered) operations. Traverse might be faster for parallel
  //We'll want Arrows when mapping over existing state channels. Need to convert State to Kleisli and vice versa
  //Add convenience methods to Cell to "flatmap" or reduce/fold over Arrows via lift
  val cellTrav = List(Cell[Int](0), Cell[Int](1), Cell[Int](2)).topologicalTraverse(g)
  cellTrav.unsafeRunAsyncAndForget()
}
*/