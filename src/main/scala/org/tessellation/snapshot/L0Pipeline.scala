package org.tessellation.snapshot

import cats.effect.{ContextShift, IO, Timer}
import cats.syntax.all._
import fs2.{Pipe, Pull, Stream}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.tessellation.StreamBlocksDemo.HeightsMap
import org.tessellation.consensus.L1Block
import org.tessellation.metrics.Metrics
import org.tessellation.schema.{CellError, Ω}

import scala.concurrent.duration.DurationInt

class L0Pipeline(metrics: Metrics) {
  private implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
  private implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)

  val tips: Stream[IO, Int] = Stream
    .range[IO](1, 1000)
    .metered(2.second)
  val tipInterval = 2
  val tipDelay = 5

  val pipeline: Pipe[IO, L1Block, Snapshot] = (in: Stream[IO, L1Block]) =>
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

                  logger
                    .debug(
                      s"Triggering snapshot at range: ${range} | Blocks: ${blocks.size} | Heights aggregated: ${heights.removedAll(range).keySet.size}"
                    )
                    .unsafeRunSync()

                  Pull.output1(L0Edge(blocks.asInstanceOf[Set[Ω]])) >> go(
                    tail,
                    (heights.removedAll(range), tip, lastEmitted + tipInterval)
                  )
                case Some((Left(tip), tail)) => go(tail, (heights, tip, lastEmitted))
                case Some((Right(block), tail)) =>
                  go(
                    tail,
                    (
                      heights.updatedWith(block.height.toInt)(_.map(_ ++ Set(block)).orElse(Set(block).some)),
                      lastTip,
                      lastEmitted
                    )
                  )
              }
          }

        in => go(in, (Map.empty, 0, 0)).stream
      }
      .map(L0Cell(_))
      .evalMap(_.run())
      .map {
        case Left(error)          => Left(error)
        case Right(ohm: Snapshot) => Right(ohm)
        case _                    => Left(CellError("Invalid Ω type"))
      }
      .map(_.right.get)
  private val logger = Slf4jLogger.getLogger[IO]
}

object L0Pipeline {

  def edges(blocks: Stream[IO, L1Block], tips: Stream[IO, Int], tipInterval: Int, tipDelay: Int)(
    implicit C: ContextShift[IO]
  ): Stream[IO, L0Edge] =
    blocks.map(_.asRight[Int]).merge(tips.map(_.asLeft[L1Block])).through {
      def go(s: Stream[IO, Either[Int, L1Block]], state: (HeightsMap, Int, Int)): Pull[IO, L0Edge, Unit] =
        state match {
          case (heights, lastTip, lastEmitted) =>
            s.pull.uncons1.flatMap {
              case None => Pull.done
              case Some((Left(tip), tail)) if tip - tipDelay >= lastEmitted + tipInterval =>
                val range = ((lastEmitted + 1) to (lastEmitted + tipInterval))
                val blocks = range.flatMap(heights.get).flatten.toSet

                Pull.output1(L0Edge(blocks.asInstanceOf[Set[Ω]])) >> go(
                  tail,
                  (heights.removedAll(range), tip, lastEmitted + tipInterval)
                )
              case Some((Left(tip), tail)) => go(tail, (heights, tip, lastEmitted))
              case Some((Right(block), tail)) =>
                go(
                  tail,
                  (
                    heights.updatedWith(block.height.toInt)(_.map(_ ++ Set(block)).orElse(Set(block).some)),
                    lastTip,
                    lastEmitted
                  )
                )
            }
        }

      in => go(in, (Map.empty, 0, 0)).stream
    }

  def runPipeline(edges: Stream[IO, L0Edge]): Stream[IO, Either[CellError, Ω]] =
    for {
      edge <- edges
      cell = L0Cell(edge)
      result <- Stream.eval {
        cell.run()
      }
    } yield result

}
