package org.tessellation

import cats.implicits._
import org.tessellation.schema.{Cell, Cell2, Context, Topos}
import cats.~>
import org.tessellation.schema.Topos._
import cats.arrow.Arrow
import cats.implicits._

object Main extends App {
  println("Hello " |+| "Cats!")
  val myDag1: Topos[Int, Int] = Cell2[Int, Int](0, 1)
  val myDag2: Topos[Int, Int] = Cell2[Int, Int](2, 3)
  val sequentialDag = myDag1 >>> myDag2
}
