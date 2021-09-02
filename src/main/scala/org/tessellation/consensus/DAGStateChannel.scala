package org.tessellation.consensus

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
import org.tessellation.metrics.{Metric, Metrics}
import org.tessellation.node.{Node, Peer}
import org.tessellation.schema.CellError

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class DAGStateChannel(node: Node, httpClient: HttpClient, metrics: Metrics) {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)
  val generateTxEvery: FiniteDuration = 2.seconds
  val maxTxs = 1000

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
          selfPeer = Peer(node.ip, node.port, node.id)
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
        .map {
          case Left(error)           => Left(error)
          case Right(block: L1Block) => Right(block)
          case _                     => Left(CellError("Invalid Ω type"))
        }
        .map(_.right.get) // TODO: Get rid of get
    } yield block
  private val logger = Slf4jLogger.getLogger[IO]
}

object DAGStateChannel {

  def init(node: Node, metrics: Metrics): Stream[IO, DAGStateChannel] = {
    implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
    for {
      blazeClient <- Stream.resource {
        BlazeClientBuilder[IO](scala.concurrent.ExecutionContext.global) // TODO: Use unbounded
          .withConnectTimeout(30.seconds)
          .withMaxTotalConnections(1024)
          .withMaxWaitQueueLimit(512)
          .resource
      }
      httpClient = HttpClient(node, blazeClient)
    } yield DAGStateChannel(node, httpClient, metrics)
  }

  def apply(node: Node, httpClient: HttpClient, metrics: Metrics): DAGStateChannel =
    new DAGStateChannel(node, httpClient, metrics)
}
