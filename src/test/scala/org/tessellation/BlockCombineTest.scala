package org.tessellation

import org.scalacheck.Properties
import cats.implicits._
import org.tessellation.schema.{Address, Block, LastTransactionRef, Transaction}

object BlockCombineTest extends Properties("BlockCombineTest") {
  property("block combine") = {

    val tx1 = Transaction(Address("a"), Address("b"), 1L, LastTransactionRef("", 1L))("keyPair1")
    val tx2 = Transaction(Address("c"), Address("d"), 2L, LastTransactionRef("", 2L))("keyPair2")
    val tx3 = Transaction(Address("e"), Address("f"), 3L, LastTransactionRef("", 3L))("keyPair3")

    val block1 = Block(List(tx1))
    val block2 = Block(List(tx2))
    val block3 = Block(List(tx3))

    println("---")
    println(block1.edge)
    println("---")
    println(block2.edge)
    println("---")
    println(block1 |+| block2 |+| block3)
    println("---")

    true == true
  }
}
