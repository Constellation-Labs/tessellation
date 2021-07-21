package org.tessellation

import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import fs2.{Pipe, Pull, Stream}
import io.chrisdavenport.fuuid.FUUID
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.generic.auto._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.tessellation.StreamBlocksDemo.HeightsMap
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
import org.tessellation.snapshot.{L0Cell, L0Edge, Snapshot}

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration.DurationInt

case class Peer(host: String, port: Int, id: String = "")

case class Node(id: String, txGenerator: RandomTransactionGenerator, ip: String = "", port: Int = 9001) {
  val edgeFactory: L1EdgeFactory = L1EdgeFactory(id)
  val cellCache: L1CellCache = L1CellCache(txGenerator)
  implicit val contextShift = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(global)

  val logger = Slf4jLogger.getLogger[IO]

  val pipelineL1: HttpClient => Pipe[IO, L1Transaction, L1Block] = (httpClient: HttpClient) =>
    (in: Stream[IO, L1Transaction]) =>
      for {
        _ <- Stream.eval(logger.debug("Start L1 Consensus Pipeline"))
        s <- Stream.eval(Semaphore[IO](2))
        txs <- in
          .through(edgeFactory.createEdges)
          .map(L1Cell(_))
          .map { l1cell => // from cache
            Stream.eval {
              s.tryAcquire.ifM(
                logger.debug(s"[Semaphore ALLOW] $l1cell") >> startL1Consensus(l1cell, httpClient)
                  .guarantee(s.release)
                  .flatTap {
                    case Right(b @ L1Block(txs)) => txs.toList.traverse(edgeFactory.ready)
                    case _                       => IO.unit
                  },
                logger.debug("[Semaphore HOLD] $l1cell") >> cellCache.cache(l1cell) >> IO {
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
            case Left(error)         => Left(error)
            case Right(ohm: L1Block) => Right(ohm)
            case _                   => Left(CellError("Invalid Ω type"))
          }
          .map(_.right.get)
      } yield txs

  val tips: Stream[IO, Int] = Stream
    .range[IO](1, 1000)
    .metered(2.second)

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

                  logger
                    .debug(
                      s"Triggering snapshot at range: ${range} | Blocks: ${blocks.size} | Heights aggregated: ${heights.removedAll(range).keySet.size}"
                    )
                    .unsafeRunSync()

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
      .map(L0Cell(_))
      .evalMap(_.run())
      .map {
        case Left(error)          => Left(error)
        case Right(ohm: Snapshot) => Right(ohm)
        case _                    => Left(CellError("Invalid Ω type"))
      }
      .map(_.right.get)

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
