package org.tessellation

import cats.effect.concurrent.Semaphore
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import fs2.Stream
import org.tessellation.consensus.transaction.L1TransactionSorter
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

      txs = Set(L1Transaction(12, "a", "b", ""))

      // cell pool
      cell = L1Cell(L1Edge(txs))

      block <- nodeA.startL1Consensus(cell)

      _ = Log.magenta(s"Output: ${block}")

    } yield ExitCode.Success
}

object WaitingPoolDemo extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {

    val generateTxEvery = 0.1.seconds
    val nTxs = 10
    val txsInChunk = 4
    val maxRoundsInProgress = 2
    val parallelJobs = 3
    val transactionGenerator = RandomTransactionGenerator()

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

    def simulate(sorter: L1TransactionSorter): Stream[IO, L1Transaction] = {
      val tx1 = L1Transaction(1, "A", "B")
      val tx2 = L1Transaction(2, "A", "B", parentHash = tx1.hash)
      val tx3 = L1Transaction(3, "A", "B", parentHash = tx2.hash)
      val tx4 = L1Transaction(4, "B", "C")
      val tx5 = L1Transaction(5, "B", "C", parentHash = tx4.hash)
      val tx6 = L1Transaction(6, "B", "C", parentHash = tx5.hash)

      Stream.emits(Seq(tx1, tx2, tx3)) ++ Stream
        .eval(sorter.done(tx1) >> IO {
          Log.red(s"[Accepted] ${tx1}")
        })
        .flatMap(
          _ =>
            Stream.emits(Seq(tx4, tx5)) ++ Stream
              .eval(sorter.done(tx4) >> IO {
                Log.red(s"[Accepted] ${tx4}")
              })
              .map(_ => tx6)
        )
    }

    val pipeline: Stream[IO, Unit] = for {
      sorter <- Stream.eval(L1TransactionSorter.apply())

      (nodeA, nodeB, nodeC) <- cluster

      s <- Stream.eval(Semaphore[IO](maxRoundsInProgress))
      txs <- simulate(sorter)
        .through(sorter.optimize)
        .evalTap(
          tx =>
            IO {
              Log.red(s"[Forward] ${tx}")
            }
        )
        .chunkN(txsInChunk)
        .map(_.toList.toSet)
        .map(L1Edge[L1Transaction])
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

      _ <- Stream.eval {
        IO {
          println(txs)
        }
      }
    } yield ()

    pipeline.compile.drain.as(ExitCode.Success)
  }
}

object StreamTransactionsDemo extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {

    val generateTxEvery = 0.1.seconds
    val nTxs = 10
    val txsInChunk = 2
    val maxRoundsInProgress = 2
    val parallelJobs = 3
    val transactionGenerator = RandomTransactionGenerator()

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
      sorter <- Stream.eval(L1TransactionSorter.apply())

      (nodeA, nodeB, nodeC) <- cluster

      s <- Stream.eval(Semaphore[IO](maxRoundsInProgress))
      txs <- transactions
        .through(sorter.optimize)
        .chunkN(txsInChunk)
        .map(_.toList.toSet)
        .map(L1Edge[L1Transaction])
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
        .flatTap(block => Stream.eval { IO { Log.magenta(block) } })

      _ <- Stream.eval { IO { println(txs) } }
    } yield ()

    pipeline.compile.drain.as(ExitCode.Success)
  }
}
