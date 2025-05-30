package io.constellationnetwork.node.shared.infrastructure.gossip

import cats.Applicative
import cats.effect.std.{Queue, Random, Supervisor}
import cats.effect.{metrics => _, _}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._

import io.constellationnetwork.node.shared.config.types.GossipRoundConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.errorShow
import io.constellationnetwork.schema.peer.Peer

import eu.timepit.refined.auto._
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
  )(implicit S: Supervisor[F]): F[GossipRoundRunner[F]] =
    for {
      selectedPeersQueue <- Queue.bounded[F, Peer](cfg.maxConcurrentRounds.value * 2)
      selectedPeersR <- Ref.of(Set.empty[Peer])
    } yield
      new GossipRoundRunner[F] {
        private val logger = Slf4jLogger.getLogger[F]

        def runForever: F[Unit] = S.supervise {
          Stream
            .fromQueueUnterminated(selectedPeersQueue)
            .parEvalMapUnordered(cfg.maxConcurrentRounds.value)(evalRound)
            .compile
            .drain
        } >> S.supervise(selectPeers.foreverM).void

        private def evalRound(peer: Peer): F[Unit] =
          MonadCancel[F].guarantee(
            Temporal[F]
              .timed(round(peer))
              .flatMap {
                case (duration, _) => metrics.recordRoundDuration(duration, roundLabel)
              }
              .flatMap(_ => metrics.incrementGossipRoundSucceeded)
              .handleErrorWith { err =>
                Metrics[F].incrementCounter("dag_gossip_round_error") >>
                  logger.error(s"Error running gossip round {peer=${peer.show}, reason=${err.show}") >>
                  localHealthcheck.start(peer)
              },
            selectedPeersR.update(_.excl(peer))
          )

        private def selectPeers: F[Unit] =
          for {
            _ <- Temporal[F].sleep(cfg.interval)
            allPeers <- clusterStorage.getResponsivePeers
            selectedPeers <- selectedPeersR.get
            availablePeers = allPeers.diff(selectedPeers)
            drawnPeers <- Random[F].shuffleList(availablePeers.toList).map(_.take(cfg.fanout.value))
            _ <- Metrics[F].recordDistribution("dag_gossip_round_all_peers", allPeers.size)
            _ <- Metrics[F].recordDistribution("dag_gossip_round_selected_peers", selectedPeers.size)
            _ <- Metrics[F].recordDistribution("dag_gossip_round_available_peers", availablePeers.size)
            _ <- Metrics[F].recordDistribution("dag_gossip_round_drawn_peers", drawnPeers.size)
            _ <- drawnPeers.traverse { peer =>
              selectedPeersR.modify { selectedPeers =>
                if (selectedPeers.contains(peer))
                  (selectedPeers, false)
                else
                  (selectedPeers.incl(peer), true)
              }.ifM(
                selectedPeersQueue.tryOffer(peer).ifM(Applicative[F].unit, selectedPeersR.update(_.excl(peer))),
                Applicative[F].unit
              )
            }
          } yield ()
      }
}
