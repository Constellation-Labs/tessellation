package org.tessellation.sdk.infrastructure.gossip

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
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GossipDaemon[F[_]] extends Daemon[F]

object GossipDaemon {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel: Metrics](
    rumorStorage: RumorStorage[F],
    rumorQueue: Queue[F, Hashed[RumorRaw]],
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
            .evalFilter(validateRumor)
            .evalFilter(verifyCollateral)
            .evalFilter(rumorStorage.addRumorIfNotSeen)
            .evalMap(handleRumor)
            .handleErrorWith { err =>
              Stream.eval(logger.error(err)(s"Unexpected error in gossip")) >> Stream.raiseError(err)
            }
            .compile
            .drain

        private def logConsumption(hashedRumor: Hashed[RumorRaw]): F[Unit] =
          rumorLogger.info(
            s"Rumor consumed {hash=${hashedRumor.hash.show}, rumor=${hashedRumor.signed.value.show}}"
          )

        private def validateRumor(hashedRumor: Hashed[RumorRaw]): F[Boolean] =
          rumorValidator
            .validate(hashedRumor.signed)
            .flatTap { signedRumorV =>
              Applicative[F]
                .whenA(signedRumorV.isInvalid)(
                  logger.warn(s"Discarding invalid rumor {hash=${hashedRumor.hash.show}, reason=${signedRumorV.show}")
                )
            }
            .map(_.isValid)

        private def verifyCollateral(hashedRumor: Hashed[RumorRaw]): F[Boolean] =
          hashedRumor.signed.proofs.toNonEmptyList
            .map(_.id)
            .map(PeerId.fromId)
            .forallM(collateral.hasCollateral)
            .flatTap(
              logger
                .warn(
                  s"Discarding rumor due to not sufficient collateral {hash=${hashedRumor.hash.show}}"
                )
                .unlessA
            )

        private def handleRumor(hashedRumor: Hashed[RumorRaw]): F[Unit] =
          rumorHandler
            .run((hashedRumor.signed.value, nodeId))
            .semiflatTap(_ => metrics.updateRumorsConsumed("success", hashedRumor))
            .getOrElseF {
              logger.debug(s"Unhandled rumor {hash=${hashedRumor.hash.show}}") >>
                metrics.updateRumorsConsumed("unhandled", hashedRumor)
            }
            .handleErrorWith { err =>
              logger.error(err)(s"Error handling rumor {hash=${hashedRumor.hash.show}}") >>
                metrics.updateRumorsConsumed("error", hashedRumor)
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
            .parEvalMapUnordered(cfg.maxConcurrentRounds)(runTimedGossipRound)
            .compile
            .drain

        private def runTimedGossipRound(peer: Peer): F[Unit] =
          Temporal[F]
            .timed(runGossipRound(peer))
            .flatMap {
              case (duration, _) => metrics.updateRoundDurationSum(duration)
            }
            .flatMap(_ => metrics.incrementGossipRoundSucceeded)
            .handleErrorWith(err =>
              logger
                .error(err)(s"Error running gossip round {peer=${peer.show}}")
            )

        private def runGossipRound(peer: Peer): F[Unit] =
          for {
            offerResponse <- gossipClient.getOffer.run(peer)
            seenHashes <- rumorStorage.getSeenHashes
            inquiryRequest = RumorInquiryRequest(offerResponse.offer.diff(seenHashes))
            _ <- Applicative[F].whenA(inquiryRequest.inquiry.nonEmpty) {
              gossipClient
                .sendInquiry(inquiryRequest)
                .run(peer)
                .evalMap(_.toHashed)
                .enqueueUnterminated(rumorQueue)
                .compile
                .drain
            }
          } yield ()

      }
    }
  }

}
