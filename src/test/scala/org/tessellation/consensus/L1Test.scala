package org.tessellation.consensus

import cats.effect.IO
import cats.implicits._
import fs2.Stream
import io.chrisdavenport.fuuid.FUUID
import org.mockito.cats.IdiomaticMockitoCats
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.BeforeAndAfter
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.Node
import org.tessellation.consensus.transaction.RandomTransactionGenerator

import scala.collection.immutable.Set

class L1Test
    extends AnyFreeSpec
    with IdiomaticMockito
    with IdiomaticMockitoCats
    with Matchers
    with ArgumentMatchersSugar
    with BeforeAndAfter {

  var nodeATxGenerator = mock[RandomTransactionGenerator]
  var nodeBTxGenerator = mock[RandomTransactionGenerator]
  var nodeCTxGenerator = mock[RandomTransactionGenerator]

  val txA = L1Transaction(1, "A", "X", "", 0)
  val txB = L1Transaction(1, "B", "X", "", 0)
  val txC = L1Transaction(1, "C", "X", "", 0)

  before {
    nodeATxGenerator.generateRandomTransaction() shouldReturn IO.pure(txA)
    nodeBTxGenerator.generateRandomTransaction() shouldReturn IO.pure(txB)
    nodeCTxGenerator.generateRandomTransaction() shouldReturn IO.pure(txC)
  }

  "Own consensus round" - {
    "creates block" in {
      val scenario = for {
        nodeA <- Node.run("nodeA", "A").map(_.copy(txGenerator = nodeATxGenerator))
        nodeB <- Node.run("nodeB", "B").map(_.copy(txGenerator = nodeBTxGenerator))
        nodeC <- Node.run("nodeC", "C").map(_.copy(txGenerator = nodeCTxGenerator))

        _ <- nodeA.joinTo(Set(nodeB, nodeC))
        _ <- nodeB.joinTo(Set(nodeA, nodeC))
        _ <- nodeC.joinTo(Set(nodeA, nodeB))

        txA <- nodeA.txGenerator.generateRandomTransaction()
        edgeA = L1Edge(Set(txA))
        cell = L1Cell(edgeA)
        result <- nodeA.startL1Consensus(cell)
      } yield result

      val result = scenario.unsafeRunSync()

      result.isRight shouldBe true
    }

    "block contains proposals from all the nodes" in {
      val scenario = for {
        nodeA <- Node.run("nodeA", "A").map(_.copy(txGenerator = nodeATxGenerator))
        nodeB <- Node.run("nodeB", "B").map(_.copy(txGenerator = nodeBTxGenerator))
        nodeC <- Node.run("nodeC", "C").map(_.copy(txGenerator = nodeCTxGenerator))

        _ <- nodeA.joinTo(Set(nodeB, nodeC))
        _ <- nodeB.joinTo(Set(nodeA, nodeC))
        _ <- nodeC.joinTo(Set(nodeA, nodeB))

        txA <- nodeA.txGenerator.generateRandomTransaction()
        edgeA = L1Edge(Set(txA))
        cell = L1Cell(edgeA)
        result <- nodeA.startL1Consensus(cell)
      } yield result

      val result = scenario.unsafeRunSync()

      result shouldBe Right(L1Block(Set(txA, txB, txC)))
    }
  }

  "Facilitator consensus round" - {
    "returns proposal" in {
      val scenario = for {
        nodeA <- Node.run("nodeA", "A").map(_.copy(txGenerator = nodeATxGenerator))
        nodeB <- Node.run("nodeB", "B").map(_.copy(txGenerator = nodeBTxGenerator))
        nodeC <- Node.run("nodeC", "C").map(_.copy(txGenerator = nodeCTxGenerator))

        _ <- nodeA.joinTo(Set(nodeB, nodeC))
        _ <- nodeB.joinTo(Set(nodeA, nodeC))
        _ <- nodeC.joinTo(Set(nodeA, nodeB))

        txA <- nodeA.txGenerator.generateRandomTransaction()
        roundIdA <- FUUID.randomFUUID[IO]
        // Consensus owner (nodeA) sends Edge as proposal
        edgeA = L1Edge(Set(txA))

        // Facilitator already has Cell in memory (it should create empty cell otherwise on demand)
        cellB = L1Cell(L1Edge(Set(txB)))

        result <- nodeB.participateInL1Consensus(roundIdA, nodeA, edgeA, cellB)
      } yield result

      val result = scenario.unsafeRunSync()
      println(result)

      result.isRight shouldBe true
    }
  }

}
