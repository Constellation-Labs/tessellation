package org.tessellation.consensus

import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.Semaphore
import fs2.{Pipe, Stream}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import cats.implicits._
import org.tessellation.http.HttpClient
import org.tessellation.metrics.{Metric, Metrics}
import org.tessellation.node.Node
import org.tessellation.schema.CellError

class L1Pipeline(node: Node, httpClient: HttpClient, metrics: Metrics) {
  private implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  val pipeline: Pipe[IO, L1Transaction, L1Block] = (in: Stream[IO, L1Transaction]) =>
    for {
      _ <- Stream.eval(logger.debug("Start L1 Consensus Pipeline"))
      _ <- Stream.eval(metrics.incrementMetricAsync[IO](Metric.L1StartPipeline))
      s <- Stream.eval(Semaphore[IO](2))
      block <- in
        .through(node.edgeFactory.createEdges)
        .map(L1Cell(_))
        .map { l1cell => // from cache
          Stream.eval {
            s.tryAcquire.ifM(
              logger.debug(s"[Semaphore ALLOW] $l1cell") >> node
                .startL1Consensus(l1cell, httpClient)
                .guarantee(s.release)
                .flatTap {
                  case Right(L1Block(txs)) => txs.toList.traverse(node.edgeFactory.ready)
                  case _                   => IO.unit
                },
              logger.debug(s"[Semaphore HOLD] $l1cell") >> metrics.incrementMetricAsync[IO](
                Metric.L1SemaphorePutToCellCache
              ) >> node.cellCache.cache(l1cell) >> IO {
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
        .parJoin(3)
        .map {
          case Left(error)           => Left(error)
          case Right(block: L1Block) => Right(block)
          case _                     => Left(CellError("Invalid Ω type"))
        }
        .map(_.right.get) // TODO: Get rid of get
    } yield block
  private val logger = Slf4jLogger.getLogger[IO]
}
