package org.tessellation.snapshot

import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO, Timer}
import cats.syntax.all._
import fs2.concurrent.Queue
import fs2.{Pipe, Pull, Stream}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.io._
import org.tessellation.StreamBlocksDemo.HeightsMap
import org.tessellation.consensus.L1Block
import org.tessellation.http.HttpClient
import org.tessellation.implicits.PipeArrow._
import org.tessellation.majority.L0MajorityCell.maxHeight
import org.tessellation.majority.SnapshotStorage.{MajorityHeight, PeersProposals, SnapshotProposal, SnapshotProposalsAtHeight}
import org.tessellation.majority._
import org.tessellation.metrics.Metrics
import org.tessellation.node.{Node, Peer}
import org.tessellation.schema.{CellError, Ω}

import scala.collection.SortedMap
import scala.concurrent.duration.DurationInt

class L0Pipeline(metrics: Metrics, node: Node, snapshotStorage: SnapshotStorage, peerProposalQueue: Queue[IO, SignedSnapshotProposal], httpClient: HttpClient) {
  private implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
  private implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)
  private val logger = Slf4jLogger.getLogger[IO]

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "snapshot" / "proposal" =>
      for {
        proposal <- req.as[SignedSnapshotProposal]
        res <- {
          proposal match {
            case OwnProposal(_) =>
              IO[SendProposalResponse](ProposalRejected)
            case p: PeerProposal =>
              logger.debug(s"Received peer proposal $p") >>
                peerProposalQueue.enqueue1(p) >>
                IO[SendProposalResponse](ProposalAccepted)
          }
        }.flatMap(Ok(_))
      } yield res
  }

  val tips: Stream[IO, Int] = Stream
    .range[IO](1, 1000)
    .metered(2.second)

  val tipInterval = 2

  val tipDelay = 5

  val channelErrorMessage = "L0StateChannel failed!"

  private val snapshotPipeline: Pipe[IO, L1Block, Snapshot] = (in: Stream[IO, L1Block]) =>
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
      .flatMap {
        case Left(error)        =>
          Stream.eval(logger.error(error)(channelErrorMessage)) >> Stream.raiseError[IO](error)

        case Right(s: Snapshot) =>
          Stream.eval(logger.debug(s"Snapshot created! hash=${s.hash}")).as(s)

        case _ =>
          val error = CellError("Invalid Ω type")
          Stream.eval(logger.error(error)(channelErrorMessage)) >> Stream.raiseError[IO](error)
      }

  val l0PeerProposalInput: Stream[IO, SignedSnapshotProposal] =
    peerProposalQueue.dequeue

  val majorityPipeline: Pipe[IO, SignedSnapshotProposal, Majority] =
    _.map(L0MajorityCell(_, L0MajorityContext(node, snapshotStorage)))
      .evalMap(_.run())
      .flatMap {
        case Left(error) =>
          Stream.eval(logger.error(error)(channelErrorMessage)) >> Stream.raiseError[IO](error)

        case Right(ProposalProcessed(proposal)) =>
          Stream.eval(logger.debug(s"Proposal=$proposal processed!")) >> Stream.empty

        case Right(m @ Majority(value)) =>
          Stream.eval(logger.debug(s"Majority picked! Majority=$value")).as(m)

        case Right(_) =>
          val error = CellError("Invalid Ω type")
          Stream.eval(logger.error(error)(channelErrorMessage)) >> Stream.raiseError[IO](error)
      }

  val majorityPipelineInStream: Pipe[IO, SignedSnapshotProposal, Majority] = in =>
    in.through {
      def go(
        s: Stream[IO, SignedSnapshotProposal],
        proposals: (SnapshotProposalsAtHeight, PeersProposals)
      ): Pull[IO, PickMajorityInStream, Unit] =
        proposals match {
          case (createdSnapshots, peerProposals) =>
            s.pull.uncons1.flatMap {
              case None => Pull.done
              case Some((proposal, tail)) =>
                for {
                  _ <- Pull.eval(logger.debug(s"some proposal=$proposal"))
                  peers <- Pull.eval(node.getPeers)
                  maxLastMajorityState <- Pull.eval(snapshotStorage.lastMajorityState.get.map(maxHeight))
                  activeBetweenHeights <- Pull.eval(snapshotStorage.activeBetweenHeights.get.map(_.getOrElse(MajorityHeight(None, None))))
                  peer = Peer(node.ip, node.port, node.id, NonEmptyList.one(activeBetweenHeights))
                  updated <- Pull
                    .pure(
                      proposal match {
                        case OwnProposal(proposal) =>
                          (createdSnapshots + (proposal.height -> proposal), peerProposals)
                        case PeerProposal(id, proposal) =>
                          val current = peerProposals.getOrElse(id, Map.empty)
                          val updatedForPeer =
                            if (current.contains(proposal.height)) current
                            else current + (proposal.height -> proposal)
                          (createdSnapshots, peerProposals + (id -> updatedForPeer))
                      }
                    )
                    .map {
                      case (created, peerProposals) =>
                        (
                          created.filter(_._1 > maxLastMajorityState),
                          peerProposals.map {
                            case (id, proposals) => id -> proposals.filter(_._1 > maxLastMajorityState)
                          }
                        )
                    }
                  (updatedCreated, updatedPeerProposals) = updated
                  peerProposalsTotal = peers.map(p => p -> updatedPeerProposals.getOrElse(p.id, Map.empty)) +
                    (peer -> updatedCreated)
                  readyToPickMajority = peerProposalsTotal.nonEmpty && peerProposalsTotal.forall {
                    case (p, proposals) =>
                      proposals.exists { case (height, _) => height == maxLastMajorityState + 2 } ||
                        !MajorityHeight.isHeightBetween(maxLastMajorityState + 2)(p.majorityHeight)

                  }
                  result <- if (readyToPickMajority)
                    Pull.output1(PickMajorityInStream(peer, peers, updatedCreated, updatedPeerProposals)) >> go(
                      tail,
                      (updatedCreated, updatedPeerProposals)
                    )
                  else
                    go(tail, (updatedCreated, updatedPeerProposals))
                } yield result
            }
        }

      in => go(in, (Map.empty, Map.empty)).stream
    }.map(L0MajorityCellInStream(_))
      .evalMap(_.run())
      .flatMap {
        case Left(error) =>
          Stream.eval(logger.error(error)(channelErrorMessage)) >> Stream.raiseError[IO](error)

        case Right(m @ Majority(value)) =>
          // TODO: not sure that it's the best place to update majority...
          Stream.eval(snapshotStorage.lastMajorityState.modify(_ => (value, ()))) >>
            Stream.eval(logger.debug(s"Majority picked! Majority=$value")).as(m)

        case Right(_) =>
          val error = CellError("Invalid Ω type")
          Stream.eval(logger.error(error)(channelErrorMessage)) >> Stream.raiseError[IO](error)
      }

  private val ownProposalPipeline: Pipe[IO, Snapshot, OwnProposal] = in =>
    in.map(
      s =>
        OwnProposal(
            SnapshotProposal(s.hash, s.blocks.minByOption(_.height).map(_.height.toLong).getOrElse(0L), SortedMap.empty)
          )
    )

  val sendProposalPipeline: Pipe[IO, OwnProposal, OwnProposal] = in =>
    in.flatTap(
      proposal =>
        Stream.eval(
          for {
            peers <- node.getPeers.map(_.toList)
            peerProposal = PeerProposal(node.id, proposal.proposal)
            _ <- peers.traverse { peer =>
              httpClient.sendSnapshotProposal(peerProposal)(peer)
                .handleErrorWith(e => logger.debug(e)(s"Proposal=${proposal.proposal} sending to $peer failed!"))
            }
          } yield ()
        )
    )

  val blockToProposalPipeline: Pipe[IO, L1Block, OwnProposal] = snapshotPipeline >>> ownProposalPipeline
}

object L0Pipeline {

  def init(metrics: Metrics, node: Node, snapshotStorage: SnapshotStorage, httpClient: HttpClient): Stream[IO, L0Pipeline] =
    Stream.eval {
      implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
      for {
        proposalQueue <- Queue.unbounded[IO, SignedSnapshotProposal]
        l0Pipeline = L0Pipeline(metrics, node, snapshotStorage, proposalQueue, httpClient)
      } yield l0Pipeline
    }

  def apply(metrics: Metrics, node: Node, snapshotStorage: SnapshotStorage, proposalQueue: Queue[IO, SignedSnapshotProposal], httpClient: HttpClient): L0Pipeline =
    new L0Pipeline(metrics, node, snapshotStorage, proposalQueue, httpClient)

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
