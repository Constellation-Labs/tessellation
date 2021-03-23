package org.tessellation

import cats.effect.concurrent.Semaphore
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import fs2.Stream
import org.tessellation.consensus.transaction.L1EdgeFactory
import org.tessellation.consensus.{L1Block, L1Cell, L1Edge, L1Transaction}
import org.tessellation.schema.CellError
import org.tessellation.consensus.transaction.RandomTransactionGenerator

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

      txs = Set(L1Transaction(12, "a", "b", "", 0))

      // cell pool
      cell = L1Cell(L1Edge(txs))

      block <- nodeA.startL1Consensus(cell)

      _ = Log.magenta(s"Output: ${block}")

    } yield ExitCode.Success
}

object PredefinedScenarioDemo extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val maxRoundsInProgress = 2
    val parallelJobs = 3
    val edgeFactory = L1EdgeFactory()

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

    def runPredefinedScenario: Stream[IO, L1Transaction] = {
      val tx1 = L1Transaction(1, "A", "B", parentHash = "", 0)
      val tx2 = L1Transaction(2, "A", "B", parentHash = tx1.hash, 1)
      val tx3 = L1Transaction(3, "A", "B", parentHash = tx2.hash, 2)
      val tx4 = L1Transaction(4, "B", "C", parentHash = "", 0)
      val tx5 = L1Transaction(5, "B", "C", parentHash = tx4.hash, 1)
      val tx6 = L1Transaction(6, "B", "C", parentHash = tx5.hash, 2)

      Stream.emits(Seq(tx1, tx2, tx3)) ++ Stream
        .eval(edgeFactory.ready(tx1) >> IO {
          Log.white(s"[ConsensusEnd] ${tx1}")
        })
        .flatMap(
          _ =>
            Stream.emits(Seq(tx4, tx5)) ++ Stream
              .eval(edgeFactory.ready(tx4) >> IO {
                Log.white(s"[ConsensusEnd] ${tx4}")
              })
              .map(_ => tx6)
        )
    }

    val pipeline: Stream[IO, Unit] = for {
      (nodeA, nodeB, nodeC) <- cluster

      s <- Stream.eval(Semaphore[IO](maxRoundsInProgress))
      txs <- runPredefinedScenario
        .through(edgeFactory.createEdges)
        .evalTap(
          edge =>
            IO {
              Log.red(s"[Edge] ${edge}")
            }
        )
        .map(L1Cell)
        .map { l1cell => // from cache
          Stream.eval {
            s.tryAcquire.ifM(
              nodeA.startL1Consensus(l1cell).guarantee(s.release),
              IO {
                println(s"store txs = ${l1cell.edge.txs}")
                L1Block(Set.empty).asRight[CellError] // TODO: ???
              }
            )
          }
        }
        .parJoin(parallelJobs)
        .flatTap(
          block =>
            Stream.eval {
              IO {
                Log.magenta(block)
              }
            }
        )
    } yield ()

    pipeline.compile.drain.as(ExitCode.Success)
  }
}

object RandomScenarioDemo extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {

    val generateTxEvery = 0.1.seconds
    val nTxs = 40
    val maxRoundsInProgress = 2
    val parallelJobs = 3
    val transactionGenerator = RandomTransactionGenerator()
    val edgeFactory = L1EdgeFactory()

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

    val transactions: Stream[IO, L1Transaction] =
      Stream
        .repeatEval(transactionGenerator.generateRandomTransaction())
        .metered(generateTxEvery)
        .take(nTxs)

    val pipeline: Stream[IO, Unit] = for {
      (nodeA, nodeB, nodeC) <- cluster

      s <- Stream.eval(Semaphore[IO](maxRoundsInProgress))
      txs <- transactions
        .through(edgeFactory.createEdges)
        .map(L1Cell)
        .map { l1cell =>
          Stream.eval {
            s.tryAcquire.ifM(
              nodeA.startL1Consensus(l1cell).guarantee(s.release),
              IO {
                println(s"store txs = ${l1cell.edge.txs}")
                L1Block(Set.empty).asRight[CellError] // TODO: ???
              }
            )

          }
        }
        .parJoin(parallelJobs)
        .flatTap {
          case Right(L1Block(txs)) => Stream.eval(txs.toList.traverse(edgeFactory.ready))
          case _                   => Stream.emit(())
        }
        .flatTap(block => Stream.eval { IO { Log.magenta(block) } })

      //      _ <- Stream.eval { IO { println(txs) } }
    } yield ()

    pipeline.compile.drain.as(ExitCode.Success)
  }
}
