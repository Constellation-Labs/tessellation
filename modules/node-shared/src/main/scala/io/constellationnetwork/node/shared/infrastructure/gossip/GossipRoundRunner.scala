package io.constellationnetwork.node.shared.infrastructure.gossip

import cats.Applicative
import cats.effect.std.{Queue, Random, Supervisor}
import cats.effect.{metrics => _, _}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.GossipRoundConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.infrastructure.fork.ExitOnFork
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.errorShow
import io.constellationnetwork.schema.peer.Peer

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
          // logger.debug(s"Starting gossip round with {peer=${peer.show}}") >>
          MonadCancel[F].guarantee(
            Temporal[F]
              .timeout(
                (for {
                  result <- Temporal[F]
                    .timed(round(peer))
                    .attempt // Catch all errors from the round itself
                  _ <- result match {
                    case Right((duration, _)) =>
                      // logger.debug(s"Gossip round succeeded {peer=${peer.show}, duration=${duration.toMillis}ms}") >>
                      metrics.recordRoundDuration(duration, roundLabel) >>
                        metrics.incrementGossipRoundSucceeded
                    case Left(err) =>
                      // logger.error(s"Error running gossip round {peer=${peer.show}, reason=${err.show}") >>
                      Temporal[F].start(localHealthcheck.start(peer)).void // Fire and forget the health check
                  }
                } yield ()).handleErrorWith { err =>
                  // Last resort error handler to ensure we never block
                  logger.error(s"Unexpected error in gossip round handler {peer=${peer.show}, reason=${err.show}")
                },
                10.seconds // Hard timeout - never let a round run longer than 10 seconds
              )
              .handleErrorWith { timeoutErr =>
                logger.error(s"Hard timeout reached for gossip round {peer=${peer.show}, reason=${timeoutErr.show}}")
              },
            selectedPeersR.update(_.excl(peer)) // >> logger.debug(s"Finished gossip round with {peer=${peer.show}}")
          )

        private def selectPeers: F[Unit] =
          for {
            _ <- Temporal[F].sleep(cfg.interval)
            allPeers <- clusterStorage.getResponsivePeers
            _ <- ExitOnFork.exitOnCheck("CL_EXIT_ON_FOLLOWER_GOSSIP", () => allPeers.map(_.id))
            selectedPeers <- selectedPeersR.get
            availablePeers = allPeers.diff(selectedPeers)
            drawnPeers <- Random[F].shuffleList(availablePeers.toList).map(_.take(cfg.fanout.value))
            _ <- drawnPeers.traverse { peer =>
              selectedPeersR.modify { selectedPeers =>
                if (selectedPeers.contains(peer))
                  (selectedPeers, false)
                else
                  (selectedPeers.incl(peer), true)
              }.ifM(
                selectedPeersQueue
                  .tryOffer(peer)
                  .ifM(
                    Applicative[F].unit,
                    // logger.debug(s"Queued peer for gossip round: {peer=${peer.show}}"), >> logger.debug(s"Queue full, removed peer: {peer=${peer.show}}
                    selectedPeersR.update(_.excl(peer))
                  ),
                Applicative[F].unit
                // logger.debug(s"Peer already selected, skipping: {peer=${peer.show}}")
              )
            }
          } yield ()
      }
}
