package org.tessellation.consensus

import cats.implicits._
import fs2.Stream
import org.mockito.cats.IdiomaticMockitoCats
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.BeforeAndAfter
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.consensus.transaction.RandomTransactionGenerator

class L1EdgeFactoryTest
    extends AnyFreeSpec
    with IdiomaticMockito
    with IdiomaticMockitoCats
    with Matchers
    with ArgumentMatchersSugar
    with BeforeAndAfter {

  val nodeId = "testNode"
  var edgeFactory: L1EdgeFactory = _

  before {
    edgeFactory = L1EdgeFactory(nodeId)
  }

  "Creating L1Edges from L1Transactions" - {

    "Creates edge for single transaction" in {
      val txA = RandomTransactionGenerator(nodeId, Some("A")).generateRandomTransaction().unsafeRunSync()
      val scenario = Stream(txA).through(edgeFactory.createEdges)

      val edge = scenario.compile.toList.map(_.head).unsafeRunSync()

      edge.txs.contains(txA) shouldBe true
    }
  }

  "Creates edges for multiple transactions from different addresses" in {
    val txA = RandomTransactionGenerator(nodeId, Some("A")).generateRandomTransaction().unsafeRunSync()
    val txB = RandomTransactionGenerator(nodeId, Some("B")).generateRandomTransaction().unsafeRunSync()
    val txC = RandomTransactionGenerator(nodeId, Some("C")).generateRandomTransaction().unsafeRunSync()

    val scenario = Stream(txA, txB, txC).through(edgeFactory.createEdges)

    val edges = scenario.compile.toList.unsafeRunSync()

    edges shouldBe List(L1Edge(Set(txA)), L1Edge(Set(txB)), L1Edge(Set(txC)))
  }

  "Puts transactions without accepted parent to waiting pool" in {
    val generator = RandomTransactionGenerator(nodeId, Some("A"))
    val tx1 = generator.generateRandomTransaction().unsafeRunSync()
    val tx2 = generator.generateRandomTransaction().unsafeRunSync()
    val tx3 = generator.generateRandomTransaction().unsafeRunSync()

    val scenario = Stream(tx1, tx2, tx3).through(edgeFactory.createEdges)

    val edges = scenario.compile.toList.unsafeRunSync()
    val waiting = edgeFactory.waitingTransactions.get.unsafeRunSync().values.flatten.toSet

    edges shouldBe List(L1Edge(Set(tx1)))

    Set(tx2, tx3).subsetOf(waiting) shouldBe true
  }

  "Moves transaction from waiting pool to ready pool if consensus ends" in {
    val generator = RandomTransactionGenerator(nodeId, Some("A"))
    val tx1 = generator.generateRandomTransaction().unsafeRunSync()
    val tx2 = generator.generateRandomTransaction().unsafeRunSync()

    val scenario = Stream(tx1, tx2).through(edgeFactory.createEdges) ++ Stream.eval(edgeFactory.ready(tx1)).dropRight(1)

    val edges = scenario.compile.toList.unsafeRunSync()
    val waiting = edgeFactory.waitingTransactions.get.unsafeRunSync().values.flatten.toSet
    val ready = edgeFactory.readyTransactions.get.unsafeRunSync().values.flatten.toSet
    println(edges)
    println(waiting)
    println(ready)

    edges shouldBe List(L1Edge(Set(tx1)))
    waiting shouldBe Set()
    ready shouldBe Set(tx2)
  }

  "Moves ready transaction from waiting pool to ready pool if consensus ends" in {
    val generator = RandomTransactionGenerator(nodeId, Some("A"))
    val tx1 = generator.generateRandomTransaction().unsafeRunSync()
    val tx2 = generator.generateRandomTransaction().unsafeRunSync()

    val scenario = Stream(tx1, tx2).through(edgeFactory.createEdges) ++ Stream.eval(edgeFactory.ready(tx1)).dropRight(1)

    val edges = scenario.compile.toList.unsafeRunSync()
    val waiting = edgeFactory.waitingTransactions.get.unsafeRunSync().values.flatten.toSet
    val ready = edgeFactory.readyTransactions.get.unsafeRunSync().values.flatten.toSet

    edges shouldBe List(L1Edge(Set(tx1)))
    waiting shouldBe Set()
    ready shouldBe Set(tx2)
  }

  "Creates edge by pulling from ready pool even if incoming transaction is incorrect (without accepted parent) " in {
    val generator = RandomTransactionGenerator(nodeId, Some("A"))
    val tx0 = generator.generateRandomTransaction().unsafeRunSync()
    val tx1 = generator.generateRandomTransaction().unsafeRunSync()
    val tx2 = generator.generateRandomTransaction().unsafeRunSync() // It produces L1Edge(Tx0)

    val scenario =
      (Stream(tx0, tx1).evalTap(_ => edgeFactory.ready(tx0)) ++ Stream(tx2)).through(edgeFactory.createEdges)

    val edges = scenario.compile.toList.unsafeRunSync()
    val waiting = edgeFactory.waitingTransactions.get.unsafeRunSync().values.flatten.toSet
    val ready = edgeFactory.readyTransactions.get.unsafeRunSync().values.flatten.toSet

    edges shouldBe List(L1Edge(Set(tx0)), L1Edge(Set(tx1)))
    waiting shouldBe Set(tx2)
    ready shouldBe Set()
  }

  "Creates edge by appending first transactions from ready pool if incoming transaction is correct" in {
    val generator = RandomTransactionGenerator(nodeId, Some("A"))
    val tx0 = generator.generateRandomTransaction().unsafeRunSync() // Edge
    val tx1 = generator.generateRandomTransaction().unsafeRunSync() // waiting
    val txB = RandomTransactionGenerator(nodeId, Some("B")).generateRandomTransaction().unsafeRunSync()

    val scenario = {
      (Stream(tx0) ++ Stream(tx1) ++ Stream.eval(edgeFactory.ready(tx0)).map(_ => txB))
        .through(edgeFactory.createEdges)
    }

    val edges = scenario.compile.toList.unsafeRunSync()
    val waiting = edgeFactory.waitingTransactions.get.unsafeRunSync().values.flatten.toSet
    val ready = edgeFactory.readyTransactions.get.unsafeRunSync().values.flatten.toSet

    edges shouldBe List(L1Edge(Set(tx0)), L1Edge(Set(tx1, txB)))
    waiting shouldBe Set()
    ready shouldBe Set()
  }

}
