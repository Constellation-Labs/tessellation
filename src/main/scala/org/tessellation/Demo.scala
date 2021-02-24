package org.tessellation

import cats.effect.concurrent.Semaphore
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import org.tessellation.schema.{L1Block, L1Edge, L1Transaction}
import fs2.Stream
import org.tessellation.schema.L1Consensus.L1ConsensusError

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

    val generateTxEvery = 0.1.seconds
    val nTxs = 10
    val txsInChunk = 2
    val maxRoundsInProgress = 2
    val parallelJobs = 3

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

      s <- Stream.eval(Semaphore[IO](maxRoundsInProgress))
      txs <- transactions
        .chunkN(txsInChunk)
        .map(_.toList.toSet)
        .map(L1Edge[L1Transaction])
        .map { edge =>
          Stream.eval {
            s.tryAcquire.ifM(
              nodeA.startL1Consensus(edge).guarantee(s.release),
              IO {
                println(s"store txs = ${edge.txs}")
                L1Block(Set.empty).asRight[L1ConsensusError] // TODO: ???
              }
            )

          }
        }
        .parJoin(parallelJobs)
        .flatTap(block => Stream.eval { IO { Log.magenta(block) } })

      _ <- Stream.eval { IO { println(txs) } }
    } yield ()

    pipeline.compile.drain.as(ExitCode.Success)
  }

  def generateRandomTransaction(): IO[L1Transaction] =
    IO.delay {
      Random.nextInt(Integer.MAX_VALUE)
    }.map(a => L1Transaction(a, "nodeA".some)).flatTap { tx =>
      IO { Log.blue(s"Generated transaction: ${tx.a}") }
    } // TODO: we hardcode generation for nodeA only
}
