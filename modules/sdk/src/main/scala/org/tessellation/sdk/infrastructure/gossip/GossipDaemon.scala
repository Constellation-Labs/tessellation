package org.tessellation.sdk.infrastructure.gossip

import cats.effect.std.{Queue, Random}
import cats.effect.{Async, Spawn, Temporal}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.show._
import cats.syntax.traverseFilter._
import cats.{Applicative, Parallel}

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.config.types.GossipDaemonConfig
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.gossip.p2p.GossipClient
import org.tessellation.sdk.infrastructure.healthcheck.ping.PingHealthCheckConsensus
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash

import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GossipDaemon[F[_]] extends Daemon[F]

object GossipDaemon {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel](
    rumorStorage: RumorStorage[F],
    rumorQueue: Queue[F, RumorBatch],
    clusterStorage: ClusterStorage[F],
    gossipClient: GossipClient[F],
    rumorHandler: RumorHandler[F],
    nodeId: PeerId,
    cfg: GossipDaemonConfig,
    healthcheck: PingHealthCheckConsensus[F]
  ): GossipDaemon[F] = new GossipDaemon[F] {

    private val logger = Slf4jLogger.getLogger[F]

    def start: F[Unit] =
      for {
        _ <- Spawn[F].start(spreadActiveRumors.foreverM).void
        _ <- Spawn[F].start(consumeRumors)
      } yield ()

    private def consumeRumors: F[Unit] =
      Stream
        .fromQueueUnterminated(rumorQueue)
        .evalMap(validateHash)
        .evalMap(validateSignature)
        .evalMap(rumorStorage.addRumors)
        .map(sortRumors)
        .flatMap(Stream.iterable)
        .evalMap(handleRumor)
        .compile
        .drain

    private def validateHash(batch: RumorBatch): F[RumorBatch] =
      batch.filterA {
        case (hash, signedRumor) =>
          signedRumor.value.hashF
            .map(_ === hash)
            .flatTap { valid =>
              if (valid) Applicative[F].unit
              else
                logger.warn(
                  s"Discarding rumor ${signedRumor.value.show} with hash ${hash.show} due to invalid hash."
                )
            }
      }

    private def validateSignature(batch: RumorBatch): F[RumorBatch] =
      batch.filterA {
        case (hash, signedRumor) =>
          signedRumor.hasValidSignature.flatTap { valid =>
            if (valid) Applicative[F].unit
            else
              logger.warn(
                s"Discarding rumor ${signedRumor.value.show} with hash ${hash.show} due to invalid hash signature."
              )
          }
      }

    private def sortRumors(batch: RumorBatch): RumorBatch =
      batch.filter { case (_, s) => s.value.isInstanceOf[CommonRumorBinary] } ++
        batch.filter { case (_, s) => s.value.isInstanceOf[PeerRumorBinary] }
          .sortBy(_._2.asInstanceOf[PeerRumorBinary].ordinal)

    private def handleRumor(har: HashAndRumor): F[Unit] = har match {
      case (hash, signedRumor) =>
        rumorHandler
          .run((signedRumor.value, nodeId))
          .getOrElseF {
            logger.debug(s"Unhandled rumor ${signedRumor.value.show} with hash ${hash.show}.")
          }
          .handleErrorWith { err =>
            logger.error(err)(
              s"Error handling rumor ${signedRumor.value.show} with hash ${hash.show}."
            )
          }
    }

    private def spreadActiveRumors: F[Unit] =
      for {
        _ <- Temporal[F].sleep(cfg.interval)
        activeHashes <- rumorStorage.getActiveHashes
        _ <- if (activeHashes.nonEmpty) runGossipRounds(activeHashes)
        else Applicative[F].unit
      } yield ()

    private def runGossipRounds(activeHashes: List[Hash]): F[Unit] =
      for {
        seenHashes <- rumorStorage.getSeenHashes
        peers <- clusterStorage.getPeers
        selectedPeers <- Random[F].shuffleList(peers.toList).map(_.take(cfg.fanout))
        _ <- selectedPeers.parTraverse { peer =>
          runGossipRound(activeHashes, seenHashes, peer).handleErrorWith { err =>
            logger.error(err)(s"Error running gossip round with peer ${peer.show}") >>
              Spawn[F].start { healthcheck.triggerCheckForPeer(peer) }.void
          }
        }
      } yield ()

    private def runGossipRound(activeHashes: List[Hash], seenHashes: List[Hash], peer: Peer): F[Unit] =
      for {
        startRequest <- StartGossipRoundRequest(activeHashes).pure[F]
        startResponse <- gossipClient.startGossiping(startRequest).run(peer)
        inquiry = startResponse.offer.diff(seenHashes)
        answer <- rumorStorage.getRumors(startResponse.inquiry)
        endRequest = EndGossipRoundRequest(answer, inquiry)
        endResponse <- gossipClient.endGossiping(endRequest).run(peer)
        _ <- rumorQueue.offer(endResponse.answer)
      } yield ()
  }

}
