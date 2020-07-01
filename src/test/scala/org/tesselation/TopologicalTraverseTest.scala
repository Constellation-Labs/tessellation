package org.tesselation

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