package org.tessellation

import cats.implicits._
import org.tessellation.schema.{Cell, Context, DAG, Topos}
import cats.~>
import org.tessellation.schema.Topos._

object Main extends App {
  println("Hello " |+| "Cats!")
  val myDag1: Topos[Int, Int] = DAG[Int, Int](0)
  val myDag2: Topos[Int, Int] = DAG[Int, Int](0)
  val dupDAG = myDag1 >>> myDag2
}
