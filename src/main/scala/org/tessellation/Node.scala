package org.tessellation

import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.{ContextShift, IO}
import cats.implicits._
import fs2.{Pipe, Stream}
import io.chrisdavenport.fuuid.FUUID
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.generic.auto._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.tessellation.config.Config
import org.tessellation.consensus.L1ConsensusStep.{L1ConsensusContext, L1ConsensusMetadata, RoundId}
import org.tessellation.consensus.transaction.RandomTransactionGenerator
import org.tessellation.consensus.{
  L1Block,
  L1Cell,
  L1CellCache,
  L1CoalgebraStruct,
  L1Edge,
  L1EdgeFactory,
  L1ParticipateInConsensusCell,
  L1StartConsensusCell,
  L1Transaction
}
import org.tessellation.http.HttpClient
import org.tessellation.schema.{Cell, CellError, StackF, Ω}

import scala.concurrent.duration.DurationInt

case class Peer(host: String, port: Int, id: String = "")

case class Node(id: String, txGenerator: RandomTransactionGenerator, ip: String = "", port: Int = 9001) {
  val edgeFactory: L1EdgeFactory = L1EdgeFactory(id)
  val cellCache: L1CellCache = L1CellCache(txGenerator)
  implicit val contextShift = IO.contextShift(scala.concurrent.ExecutionContext.global)

  val logger = Slf4jLogger.getLogger[IO]

  val l1ConsensusPipeline: HttpClient => Pipe[IO, L1Transaction, Unit] = (httpClient: HttpClient) =>
    (txStream: Stream[IO, L1Transaction]) =>
      for {
        _ <- Stream.eval(logger.debug("Start L1 Consensus Pipeline"))
        s <- Stream.eval(Semaphore[IO](2))
        txs <- txStream
          .through(edgeFactory.createEdges)
          .map(L1Cell(_))
          .map { l1cell =>
            Stream.eval {
              s.tryAcquire.ifM(
                logger.debug(s"[Semaphore ALLOW] $l1cell") >> startL1Consensus(l1cell, httpClient)
                  .guarantee(s.release)
                  .flatTap {
                    case Right(b @ L1Block(txs)) => txs.toList.traverse(edgeFactory.ready).void
                    case _                       => IO.unit
                  },
                logger.debug("[Semaphore HOLD] $l1cell") >> cellCache.cache(l1cell)
              )

            }
          }
          .parJoin(3)
      } yield ()

  private val peers = Ref.unsafe[IO, Map[String, Peer]](Map.empty[String, Peer])

  def enoughPeersForConsensus: IO[Boolean] = peers.get.map(_.size >= 2)

  def joinTo(peer: Peer): IO[Unit] = updatePeers(peer)

  def updatePeers(peer: Peer): IO[Unit] =
    peers.modify(p => (p.updated(peer.id, peer), ()))

  def getPeers: IO[Set[Peer]] =
    peers.get.map(_.values.toSet)

  def participateInL1Consensus(
    roundId: RoundId,
    facilitatorId: String,
    consensusOwnerId: String,
    proposal: L1Edge,
    facilitators: Set[Peer],
    httpClient: HttpClient
  ): IO[Either[CellError, Ω]] =
    for {
      _ <- logger.debug(
        s"[Participate] Received proposal $proposal from $facilitatorId for round $roundId owned by $consensusOwnerId."
      )
      peers <- peers.get.map(_.values.toSet)

      cachedCell <- cellCache.get(roundId).flatMap {
        case Some(cell) => cell.pure[IO]
        case None =>
          txGenerator
            .generateRandomTransaction()
            .map(tx => L1Cell(L1Edge(Set(tx)))) // TODO: Just for testing purpose. It should be empty cell with empty set of txs!
      }

      context = L1ConsensusContext(
        selfId = id,
        peers = peers,
        txGenerator = txGenerator,
        httpClient = httpClient
      )
      metadata = L1ConsensusMetadata
        .empty(context)
        .copy(facilitators = facilitators.some, consensusOwnerId = consensusOwnerId.some)
      l1Cell = L1ParticipateInConsensusCell.fromCell[IO, StackF](cachedCell)(
        metadata,
        roundId,
        facilitatorId,
        proposal
      )
      ohm <- l1Cell.run()
    } yield ohm

  def startL1Consensus(
    cell: Cell[IO, StackF, L1Edge, Either[CellError, Ω], L1CoalgebraStruct],
    httpClient: HttpClient
  ): IO[Either[CellError, Ω]] =
    for {
      peers <- peers.get
      context = L1ConsensusContext(
        selfId = id,
        peers = peers.values.toSet,
        txGenerator = txGenerator,
        httpClient = httpClient
      )
      metadata = L1ConsensusMetadata.empty(context).copy(consensusOwnerId = id.some)
      l1Cell = L1StartConsensusCell.fromCell[IO, StackF](cell)(metadata)
      //      _ <- logger.debug(s"Starting L1 Consensus with peers: ${context.peers.map(_.host)}")
      _ <- IO.sleep(1.second)(IO.timer(scala.concurrent.ExecutionContext.global))
      ohm <- l1Cell.run()
    } yield ohm
}

object Node {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  def run(id: String, txSrc: String): IO[Node] =
    for {
      node <- IO.pure {
        Node(id, RandomTransactionGenerator(id, Some(txSrc)))
      }
    } yield node
}
