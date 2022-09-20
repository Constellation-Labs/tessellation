package org.tessellation.sdk.infrastructure.gossip

import cats.effect.std.{Queue, Random}
import cats.effect.{Async, Spawn, Temporal}
import cats.syntax.all._

import org.tessellation.schema.peer.Peer
import org.tessellation.sdk.config.types.GossipRoundConfig
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.infrastructure.metrics.Metrics

import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GossipRoundRunner[F[_]] {

  def runForever: F[Unit]

}

object GossipRoundRunner {

  def make[F[_]: Async: Random: Metrics](
    clusterStorage: ClusterStorage[F],
    localHealthcheck: LocalHealthcheck[F],
    round: Peer => F[Unit],
    roundLabel: String,
    cfg: GossipRoundConfig
  ): GossipRoundRunner[F] = new GossipRoundRunner[F] {
    private val logger = Slf4jLogger.getLogger[F]

    def runForever: F[Unit] =
      Queue.dropping[F, Peer](cfg.maxConcurrentRounds.value * 2).flatMap { selectedPeerQueue =>
        def evalRound(peer: Peer): F[Unit] =
          Temporal[F]
            .timed(round(peer))
            .flatMap {
              case (duration, _) => metrics.recordRoundDuration(duration, roundLabel)
            }
            .flatMap(_ => metrics.incrementGossipRoundSucceeded)
            .handleErrorWith { err =>
              logger.error(s"Error running gossip round {peer=${peer.show}, reason=${err.getMessage}") >>
                localHealthcheck.start(peer)
            }

        def selectPeers: F[Unit] =
          for {
            _ <- Temporal[F].sleep(cfg.interval)
            peers <- clusterStorage.getResponsivePeers
            selectedPeers <- Random[F].shuffleList(peers.toList).map(_.take(cfg.fanout.value))
            _ <- selectedPeers.traverse(selectedPeerQueue.offer)
          } yield ()

        Spawn[F].start {
          Stream
            .fromQueueUnterminated(selectedPeerQueue)
            .parEvalMapUnordered(cfg.maxConcurrentRounds.value)(evalRound)
            .compile
            .drain
        } >> Spawn[F].start(selectPeers.foreverM).void
      }
  }

}
