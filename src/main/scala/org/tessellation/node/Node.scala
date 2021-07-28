package org.tessellation.node

import cats.effect.concurrent.Ref
import cats.effect.{IO, Timer}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.tessellation.consensus.L1ConsensusStep.{L1ConsensusContext, L1ConsensusMetadata, RoundId}
import org.tessellation.consensus.transaction.RandomTransactionGenerator
import org.tessellation.consensus._
import org.tessellation.metrics.Metric._
import cats.implicits._
import org.tessellation.http.HttpClient
import org.tessellation.metrics.{Metric, Metrics}
import org.tessellation.schema.{Cell, CellError, StackF, Ω}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.global

case class Node(
  id: String,
  metrics: Metrics,
  txGenerator: RandomTransactionGenerator,
  ip: String = "",
  port: Int = 9001
) {
  val edgeFactory: L1EdgeFactory = L1EdgeFactory(id)
  val cellCache: L1CellCache = L1CellCache(txGenerator)

  private implicit val contextShift = IO.contextShift(scala.concurrent.ExecutionContext.global)
  private val logger = Slf4jLogger.getLogger[IO]
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
      _ <- metrics.incrementMetricAsync[IO](Metric.L1ParticipateInConsensus)
      _ <- logger.debug(
        s"[L1][Participate] Received proposal $proposal from $facilitatorId for round $roundId owned by $consensusOwnerId."
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
      _ <- metrics.incrementMetricAsync[IO](Metric.L1ParticipateInConsensus.success)
    } yield ohm

  def startL1Consensus(
    cell: Cell[IO, StackF, L1Edge, Either[CellError, Ω], L1CoalgebraStruct],
    httpClient: HttpClient
  ): IO[Either[CellError, Ω]] =
    for {
      _ <- metrics.incrementMetricAsync[IO](Metric.L1StartConsensus)
      peers <- peers.get
      context = L1ConsensusContext(
        selfId = id,
        peers = peers.values.toSet,
        txGenerator = txGenerator,
        httpClient = httpClient
      )
      metadata = L1ConsensusMetadata.empty(context).copy(consensusOwnerId = id.some)
      l1Cell = L1StartConsensusCell.fromCell[IO, StackF](cell)(metadata)
      _ <- IO.sleep(1.second)(IO.timer(scala.concurrent.ExecutionContext.global))
      ohm <- l1Cell.run()
      _ <- metrics.incrementMetricAsync[IO](Metric.L1StartConsensus.success)
    } yield ohm
}
