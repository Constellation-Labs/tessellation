package org.tessellation.consensus

import cats.data.NonEmptyList
import cats.effect.concurrent.Semaphore
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import fs2.{Pipe, Stream}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.generic.auto._
import io.circe.syntax.EncoderOps
import org.http4s.HttpRoutes
import org.http4s.circe._
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.dsl.io._
import org.tessellation.consensus.L1ConsensusStep.{BroadcastProposalPayload, BroadcastProposalResponse}
import org.tessellation.http.HttpClient
import org.tessellation.majority.SnapshotStorage
import org.tessellation.majority.SnapshotStorage.MajorityHeight
import org.tessellation.metrics.{Metric, Metrics}
import org.tessellation.node.{Node, Peer}
import org.tessellation.schema.CellError

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class DAGStateChannel(node: Node, snapshotStorage: SnapshotStorage, httpClient: HttpClient, metrics: Metrics) {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)
  val generateTxEvery: FiniteDuration = 2.seconds
  val maxTxs = 1000

  val channelErrorMessage = "DAGStateChannel failed!"

  val routes: HttpRoutes[IO] = HttpRoutes
    .of[IO] {
      case GET -> Root / "debug" / "peers" =>
        for {
          peers <- node.getPeers
          res <- Ok(peers.asJson)
        } yield res
      case req @ POST -> Root / "join" =>
        implicit val decoder = jsonOf[IO, Peer]
        for {
          joiningPeer <- req.as[Peer]
          _ <- node.updatePeers(joiningPeer)
          activeBetweenHeights <- snapshotStorage.activeBetweenHeights.get.map(_.getOrElse(MajorityHeight(None, None)))
          selfPeer = Peer(node.ip, node.port, node.id, NonEmptyList.one(activeBetweenHeights))
          _ <- logger.info(s"$joiningPeer joined to $selfPeer")
          res <- Ok(selfPeer.asJson)
        } yield res
      case req @ POST -> Root / "proposal" =>
        implicit val decoder = jsonOf[IO, BroadcastProposalPayload]
        for {
          request <- req.as[BroadcastProposalPayload]
          _ <- logger.info(
            s"Received proposal: ${request.proposal} for round ${request.roundId} and facilitators ${request.facilitators}"
          )

          consensus <- node.participateInL1Consensus(
            request.roundId,
            request.senderId,
            request.consensusOwnerId,
            L1Edge(request.proposal),
            request.facilitators,
            httpClient
          )
          res <- consensus match {
            case Right(ProposalResponse(txs)) =>
              Ok(BroadcastProposalResponse(request.roundId, request.proposal, txs).asJson)
            case Left(CellError(reason)) => InternalServerError(reason)
            case _                       => InternalServerError()
          }
        } yield res
    }

  val l1Input: Stream[IO, L1Transaction] = Stream
    .repeatEval(node.enoughPeersForConsensus)
    .map(hasFacilitatorsForConsensus => hasFacilitatorsForConsensus)
    .dropWhile(!_)
    .evalMap(_ => node.txGenerator.generateRandomTransaction())
    .evalTap(tx => logger.debug(s"$tx"))
    .metered(generateTxEvery)

  val l1: Pipe[IO, L1Transaction, L1Block] = (in: Stream[IO, L1Transaction]) =>
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
        .flatMap {
          case Left(error)           =>
            // TODO: raiseError will stop the stream, we may just emit an empty stream, or return an either e.g.
            Stream.eval(logger.error(error)(channelErrorMessage)) >> Stream.raiseError[IO](error)

          case Right(block: L1Block) =>
            Stream.eval(logger.debug(s"Block created! block=$block")).as(block)

          case _                     =>
            val error = CellError("Invalid Ω type")
            Stream.eval(logger.error(error)(channelErrorMessage)) >> Stream.raiseError[IO](error)
        }
    } yield block
  private val logger = Slf4jLogger.getLogger[IO]
}

object DAGStateChannel {

  def apply(node: Node, snapshotStorage: SnapshotStorage, httpClient: HttpClient, metrics: Metrics): DAGStateChannel =
    new DAGStateChannel(node, snapshotStorage, httpClient, metrics)
}
