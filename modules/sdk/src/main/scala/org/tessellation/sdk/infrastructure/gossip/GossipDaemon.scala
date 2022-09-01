package org.tessellation.sdk.infrastructure.gossip

import cats.Order._
import cats.effect.std.{Queue, Random}
import cats.effect.{Async, Spawn, Temporal}
import cats.syntax.all._
import cats.{Applicative, Parallel}

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.config.types.GossipDaemonConfig
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.domain.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient
import org.tessellation.sdk.infrastructure.healthcheck.ping.PingHealthCheckConsensus
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GossipDaemon[F[_]] extends Daemon[F]

object GossipDaemon {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel: Metrics](
    rumorStorage: RumorStorage[F],
    rumorQueue: Queue[F, RumorBatch],
    clusterStorage: ClusterStorage[F],
    gossipClient: GossipClient[F],
    rumorHandler: RumorHandler[F],
    rumorValidator: RumorValidator[F],
    nodeId: PeerId,
    cfg: GossipDaemonConfig,
    healthcheck: PingHealthCheckConsensus[F],
    collateral: Collateral[F]
  ): F[GossipDaemon[F]] = {
    Queue.dropping[F, Peer](cfg.maxConcurrentRounds * 2).map { selectedPeerQueue =>
      new GossipDaemon[F] {
        private val logger = Slf4jLogger.getLogger[F]
        private val rumorLogger = Slf4jLogger.getLoggerFromName[F](rumorLoggerName)

        def start: F[Unit] =
          Spawn[F].start(selectPeers.foreverM) >>
            Spawn[F].start(runGossipRounds) >>
            Spawn[F].start(consumeRumors).void

        private def consumeRumors: F[Unit] =
          Stream
            .fromQueueUnterminated(rumorQueue)
            .evalTap(logConsumption)
            .evalMap(validateRumors)
            .evalMap(verifyCollateral)
            .map(sortRumors)
            .evalMap(rumorStorage.addRumors)
            .flatMap(Stream.iterable)
            .evalMap(handleRumor)
            .handleErrorWith { err =>
              Stream.eval(logger.error(err)(s"Unexpected error in gossip")) >> Stream.raiseError(err)
            }
            .compile
            .drain

        private def logConsumption(batch: RumorBatch): F[Unit] =
          batch.traverse {
            case (hash, signedRumor) =>
              rumorLogger.info(
                s"Rumor consumed {hash=${hash.show}, contentType=${signedRumor.contentType}, signer=${signedRumor.proofs.head.id}}"
              )
          }.void

        private def validateRumors(batch: RumorBatch): F[RumorBatch] =
          batch.traverseFilter { har =>
            rumorValidator
              .validate(har)
              .flatTap { harV =>
                Applicative[F]
                  .whenA(harV.isInvalid)(logger.warn(s"Discarding invalid rumor {hash=${har._1.show}, reason=${harV.show}"))
              }
              .map(_.toOption)
          }

        private def verifyCollateral(batch: RumorBatch): F[RumorBatch] =
          batch.filterA {
            case (hash, signedRumor) =>
              signedRumor.proofs.toNonEmptyList
                .map(_.id)
                .map(PeerId.fromId)
                .forallM(collateral.hasCollateral)
                .flatTap(
                  logger
                    .warn(
                      s"Discarding rumor ${signedRumor.value.show} with hash ${hash.show} due to not sufficient collateral"
                    )
                    .unlessA
                )
          }

        private def sortRumors(batch: RumorBatch): RumorBatch =
          batch.filter { case (_, s) => s.value.isInstanceOf[CommonRumorBinary] } ++
            batch.filter { case (_, s) => s.value.isInstanceOf[PeerRumorBinary] }
              .sortBy(_._2.asInstanceOf[Signed[PeerRumorBinary]].ordinal)

        private def handleRumor(har: HashAndRumor): F[Unit] = har match {
          case (hash, signedRumor) =>
            rumorHandler
              .run((signedRumor.value, nodeId))
              .semiflatTap(_ => metrics.updateRumorsConsumed("success", signedRumor))
              .getOrElseF {
                logger.debug(s"Unhandled rumor ${signedRumor.value.show} with hash ${hash.show}") >>
                  metrics.updateRumorsConsumed("unhandled", signedRumor)
              }
              .handleErrorWith { err =>
                logger.error(err)(s"Error handling rumor ${signedRumor.value.show} with hash ${hash.show}") >>
                  metrics.updateRumorsConsumed("error", signedRumor)
              }
        }

        private def selectPeers: F[Unit] =
          for {
            _ <- Temporal[F].sleep(cfg.interval)
            peers <- clusterStorage.getPeers
            selectedPeers <- Random[F].shuffleList(peers.toList).map(_.take(cfg.fanout))
            _ <- selectedPeers.traverse(selectedPeerQueue.offer)
          } yield ()

        private def runGossipRounds: F[Unit] =
          Stream
            .fromQueueUnterminated(selectedPeerQueue)
            .parEvalMapUnordered(cfg.maxConcurrentRounds) { peer =>
              runTimedGossipRound(peer).handleErrorWith { err =>
                logger.error(err)(s"Error running gossip round with peer ${peer.show}") >>
                  Spawn[F].start(healthcheck.triggerCheckForPeer(peer)).void
              }
            }
            .compile
            .drain

        private def runTimedGossipRound(peer: Peer): F[Unit] =
          Temporal[F].timeout(
            Temporal[F].timed(runGossipRound(peer)).flatMap {
              case (duration, _) => metrics.updateRoundDurationSum(duration)
            },
            cfg.roundTimeout
          ) >> metrics.incrementGossipRoundSucceeded

        private def runGossipRound(peer: Peer): F[Unit] =
          for {
            activeHashes <- rumorStorage.getActiveHashes
            startRequest = StartGossipRoundRequest(activeHashes)
            startResponse <- gossipClient.startGossiping(startRequest).run(peer)
            seenHashes <- rumorStorage.getSeenHashes
            inquiry = startResponse.offer.diff(seenHashes)
            answer <- rumorStorage.getRumors(startResponse.inquiry)
            endRequest = EndGossipRoundRequest(answer, inquiry)
            _ <- metrics.updateRumorsSent(answer)
            endResponse <- gossipClient.endGossiping(endRequest).run(peer)
            _ <- rumorQueue.offer(endResponse.answer)
            _ <- metrics.updateRumorsReceived(endResponse.answer)
          } yield ()
      }
    }
  }

}
