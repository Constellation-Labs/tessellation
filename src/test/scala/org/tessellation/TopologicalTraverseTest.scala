package org.tessellation

import cats.effect.IO
import cats.implicits._
import org.scalacheck.Properties
import org.tessellation.schema.Cell
import org.tessellation.schema.Enrichment.TopEnrichedTraverse

import scala.collection.mutable.ListBuffer

object TopologicalTraverseTest extends Properties("TopologicalTraverseTest") {
  property("Enrichment traverses sequentially") = {
    var results = new ListBuffer[Int]()


    def g(i: Cell[Int, Int]): IO[Int] = IO {
      Thread.sleep(i.data * 100)
      results = results :+ i.data
      println(i.data)
      i.data
    }

    //Note: we want topologicalTraverse for Stateful (Ordered) operations. Traverse might be faster for parallel
    //We'll want Arrows when mapping over existing state channels. Need to convert State to Kleisli and vice versa
    //Add convenience methods to Cell to "flatmap" or reduce/fold over Arrows via lift
    val cellTrav = List(Cell[Int, Int](0), Cell[Int, Int](1), Cell[Int, Int](2)).topologicalTraverse(g)
    val res = cellTrav.unsafeRunSync()
    res == results.toList
  }
}
