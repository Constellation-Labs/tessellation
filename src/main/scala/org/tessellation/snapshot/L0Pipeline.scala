package org.tessellation.snapshot

import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import fs2.{Pull, Stream}
import org.tessellation.Log
import org.tessellation.StreamBlocksDemo.HeightsMap
import org.tessellation.consensus.L1Block
import org.tessellation.schema.{CellError, Ω}

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

                Pull.output1(L0Edge(blocks)) >> go(tail, (heights.removedAll(range), tip, lastEmitted + tipInterval))
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

  def pipeline(edges: Stream[IO, L0Edge]): Stream[IO, Either[CellError, Ω]] =
    for {
      edge <- edges
      cell = L0Cell(edge)
      result <- Stream.eval { cell.run() }
    } yield result

}
