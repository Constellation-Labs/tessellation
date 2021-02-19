package org.tessellation

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import org.tessellation.schema.{L1Edge, L1Transaction}
import fs2.Stream

import scala.concurrent.duration._
import scala.util.Random

object SingleL1ConsensusDemo extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    for {
      nodeA <- Node.run("nodeA")
      nodeB <- Node.run("nodeB")
      nodeC <- Node.run("nodeC")

      _ <- nodeA.joinTo(Set(nodeB, nodeC))
      _ <- nodeB.joinTo(Set(nodeA, nodeC))
      _ <- nodeC.joinTo(Set(nodeA, nodeB))

      txs = Set(L1Transaction(12, "nodeA".some))

      block <- nodeA.startL1Consensus(L1Edge(txs))

      _ = Log.magenta(s"Output: ${block}")

    } yield ExitCode.Success
}

object StreamTransactionsDemo extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {

    val generateTxEvery = 10.milliseconds
    val nTxs = 10
    val txsInChunk = 2
    val maxRoundsInProgress = 1

    val cluster: Stream[IO, (Node, Node, Node)] = Stream.eval {
      for {
        nodeA <- Node.run("nodeA")
        nodeB <- Node.run("nodeB")
        nodeC <- Node.run("nodeC")

        _ <- nodeA.joinTo(Set(nodeB, nodeC))
        _ <- nodeB.joinTo(Set(nodeA, nodeC))
        _ <- nodeC.joinTo(Set(nodeA, nodeB))
      } yield (nodeA, nodeB, nodeC)
    }

    val transactions: Stream[IO, L1Transaction] = Stream
      .repeatEval(generateRandomTransaction())
      .metered(generateTxEvery)
      .take(nTxs)

    val pipeline: Stream[IO, Unit] = for {
      (nodeA, nodeB, nodeC) <- cluster

      txs <- transactions.flatMap(
        tx =>
          Stream.eval {
            nodeA.countRoundsInProgress.map(_ < maxRoundsInProgress).ifM((tx, true).pure[IO], (tx, false).pure[IO])
          }
      )
      _ <- Stream.eval { IO { println(txs) } }
      /*
      ownRoundTxs = transactions.evalFilter { tx =>
        nodeA.countRoundsInProgress
          .map(_ < maxRoundsInProgress)
          .flatTap { filter =>
            if (filter) {
              IO.delay { println("own") }
            } else IO.unit
          }
      }.chunkN(txsInChunk)
        .map(_.toList.toSet)
        .map(L1Edge[L1Transaction])
        .flatMap(edge => Stream.eval { nodeA.startL1Consensus(edge) })
        .flatTap(block => Stream.eval { IO { Log.magenta(block) } })

      poolTxs = transactions.evalFilter { tx =>
        nodeA.countRoundsInProgress
          .map(_ >= maxRoundsInProgress)
          .flatTap { filter =>
            if (filter) {
              IO.delay { println("store in pool") }
            } else IO.unit
          }
          .map(_ => false)
      }

      _ <- ownRoundTxs.merge(poolTxs)
     */
    } yield ()

    pipeline.compile.drain.as(ExitCode.Success)
  }

  def generateRandomTransaction(): IO[L1Transaction] =
    IO.delay {
      Random.nextInt(Integer.MAX_VALUE)
    }.map(a => L1Transaction(a, "nodeA".some)) // TODO: we hardcode generation for nodeA only
}
