package org.tessellation

import cats.arrow.Arrow
import cats.effect.concurrent.Semaphore
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import fs2.concurrent.Signal
import fs2.{Pipe, Pull, Stream}
import org.tessellation.StreamTransactionsDemo.generateRandomTransaction
import org.tessellation.consensus.{L1Block, L1Cell, L1Edge, L1Transaction}
import org.tessellation.schema.{CellError, Ω}
import org.tessellation.snapshot.L0Pipeline.edges
import org.tessellation.snapshot.{L0Cell, L0Edge, L0Pipeline, Snapshot}

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

      // cell pool
      cell = L1Cell(L1Edge(txs))

      block <- nodeA.startL1Consensus(cell)

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
        .map(L1Edge)
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

  def generateRandomTransaction(): IO[L1Transaction] =
    IO.delay {
      Random.nextInt(Integer.MAX_VALUE)
    }.map(a => L1Transaction(a, "nodeA".some)).flatTap { tx =>
      IO {
        Log.blue(s"Generated transaction: ${tx.a}")
      }
    } // TODO: we hardcode generation for nodeA only
}

object PipeArrow {
  implicit val arrowInstance: Arrow[Pipe[IO, *, *]] = new Arrow[Pipe[IO, *, *]] {
    override def lift[A, B](f: A => B): Pipe[IO, A, B] = (a: Stream[IO, A]) => a.map(f)

    override def compose[A, B, C](f: Pipe[IO, B, C], g: Pipe[IO, A, B]): Pipe[IO, A, C] =
      (a: Stream[IO, A]) => f.compose(g)(a)

    override def first[A, B, C](fa: Pipe[IO, A, B]): Pipe[IO, (A, C), (B, C)] =
      (a: Stream[IO, (A, C)]) =>
        a.flatMap {
          case (aa, ac) => fa(Stream(aa)).map((_, ac))
        }
  }
}

object StreamBlocksDemo extends IOApp {

  val tips: Stream[IO, Int] = Stream
    .range[IO](1, 1000)
    .metered(2.second)

  val blocks: Stream[IO, L1Block] = Stream
    .range[IO](1, 100)
    .map(L1Transaction(_))
    .map(Set(_))
    .map(L1Block)
    .metered(1.second)

  type Height = Int
  type HeightsMap = Map[Height, Set[L1Block]]

  override def run(args: List[String]): IO[ExitCode] = {
    val pipeline = L0Pipeline.pipeline(edges(blocks, tips, tipInterval = 2, tipDelay = 5))

    val l0: Stream[IO, Unit] = pipeline
      .mapFilter(_.toOption) // pipeline returns Either[CellError, Ω] but we want to broadcast correct snapshots only
      .broadcastThrough(
        (in: Stream[IO, Ω]) =>
          in.flatMap { snapshot =>
            Stream.eval(IO(Log.cyan(s"Acceptance: ${snapshot}")))
          },
        (in: Stream[IO, Ω]) =>
          in.flatMap { snapshot =>
            Stream.eval(IO(Log.green(s"Majority state chooser: ${snapshot}")))
          }
        // and other subscribers...
      )

    l0.compile.drain.as(ExitCode.Success)
  }
}

object ArrowDemo extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {

    val generateTxEvery = 0.1.seconds
    val txsInChunk = 1
    val maxRoundsInProgress = 2
    val parallelJobs = 1

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
      .range[IO](1, 1000)
      .map(L1Transaction(_))
      .metered(generateTxEvery)

    val pipelineL1: Pipe[IO, L1Transaction, L1Block] = (in: Stream[IO, L1Transaction]) =>
      for {
        (nodeA, nodeB, nodeC) <- cluster

        s <- Stream.eval(Semaphore[IO](maxRoundsInProgress))
        txs <- in
          .chunkN(txsInChunk)
          .map(_.toList.toSet)
          .map(L1Edge)
          .map(L1Cell)
          .map { l1cell => // from cache
            Stream.eval {
              s.tryAcquire.ifM(
                nodeA.startL1Consensus(l1cell).guarantee(s.release),
                IO {
                  L1Block(Set.empty).asRight[CellError] // TODO: ???
                }
              )

            }
          }
          .map(
            _.filter(
              e =>
                e.map {
                  case b: L1Block => b.txs.nonEmpty
                }.fold(_ => true, identity)
            )
          )
          .parJoin(parallelJobs)
          .map {
            case Left(error)         => Left(error)
            case Right(ohm: L1Block) => Right(ohm)
            case _                   => Left(CellError("Invalid Ω type"))
          }
          .map(_.right.get)
      } yield txs

    val tips: Stream[IO, Int] = Stream
      .range[IO](1, 1000)
      .metered(2.second)

    type Height = Int
    type HeightsMap = Map[Height, Set[L1Block]]
    val tipInterval = 2
    val tipDelay = 5

    val pipelineL0: Pipe[IO, L1Block, Snapshot] = (in: Stream[IO, L1Block]) =>
      in.map(_.asRight[Int])
        .merge(tips.map(_.asLeft[L1Block]))
        .through {
          def go(s: Stream[IO, Either[Int, L1Block]], state: (HeightsMap, Int, Int)): Pull[IO, L0Edge, Unit] =
            state match {
              case (heights, lastTip, lastEmitted) =>
                s.pull.uncons1.flatMap {
                  case None => Pull.done
                  case Some((Left(tip), tail)) if tip - tipDelay >= lastEmitted + tipInterval =>
                    val range = ((lastEmitted + 1) to (lastEmitted + tipInterval))
                    val blocks = range.flatMap(heights.get).flatten.toSet

                    Log.yellow(
                      s"Triggering snapshot at range: ${range} | Blocks: ${blocks.size} | Heights aggregated: ${heights.removedAll(range).keySet.size}"
                    )

                    Pull.output1(L0Edge(blocks)) >> go(
                      tail,
                      (heights.removedAll(range), tip, lastEmitted + tipInterval)
                    )
                  case Some((Left(tip), tail)) => go(tail, (heights, tip, lastEmitted))
                  case Some((Right(block), tail)) =>
                    go(
                      tail,
                      (
                        heights.updatedWith(block.height)(_.map(_ ++ Set(block)).orElse(Set(block).some)),
                        lastTip,
                        lastEmitted
                      )
                    )
                }
            }

          in => go(in, (Map.empty, 0, 0)).stream
        }
        .map(L0Cell)
        .evalMap(_.run())
        .map {
          case Left(error)          => Left(error)
          case Right(ohm: Snapshot) => Right(ohm)
          case _                    => Left(CellError("Invalid Ω type"))
        }
        .map(_.right.get)

    import PipeArrow._

    val pipeline = (pipelineL1 >>> pipelineL0)(transactions)

    pipeline
      .flatTap(
        snapshot =>
          Stream.eval {
            IO {
              println(snapshot)
            }
          }
      )
      .compile
      .drain
      .as(ExitCode.Success)
  }
}
