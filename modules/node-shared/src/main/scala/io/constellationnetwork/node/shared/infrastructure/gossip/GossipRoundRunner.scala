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
import io.constellationnetwork.node.shared.infrastructure.fork.ExitOnFork
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.errorShow
import io.constellationnetwork.schema.peer.{Peer, PeerId}

import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GossipRoundRunner[F[_]] {

  def runForever: F[Unit]

}

object GossipRoundRunner {

  private final case class PeerFailureState(consecutiveFailures: Int, excludedUntilMs: Option[Long])
  private final case class PeerSelectionState(excluded: Set[PeerId], suspect: Set[PeerId])

  def make[F[_]: Async: Random: Metrics](
    clusterStorage: ClusterStorage[F],
    localHealthcheck: LocalHealthcheck[F],
    round: Peer => F[Unit],
    roundLabel: String,
    cfg: GossipRoundConfig
  )(implicit S: Supervisor[F]): F[GossipRoundRunner[F]] =
    for {
      selectedPeersQueue <- Queue.bounded[F, Peer](cfg.maxConcurrentRounds.value * 2)
      suspectPeersQueue <- Queue.bounded[F, Peer](cfg.maxConcurrentSuspectRounds.value * 2)
      selectedPeersR <- Ref.of(Set.empty[Peer])
      gossipFailuresR <- Ref.of(Map.empty[PeerId, PeerFailureState])
    } yield
      new GossipRoundRunner[F] {
        private val logger = Slf4jLogger.getLogger[F]
        private val failureWindowMs = cfg.failureWindow.toMillis
        private val failureThreshold = cfg.failureCountThreshold.value

        def runForever: F[Unit] = S.supervise {
          Stream
            .fromQueueUnterminated(selectedPeersQueue)
            .parEvalMapUnordered(cfg.maxConcurrentRounds.value)(evalRound)
            .compile
            .drain
        } >> S.supervise {
          Stream
            .fromQueueUnterminated(suspectPeersQueue)
            .parEvalMapUnordered(cfg.maxConcurrentSuspectRounds.value)(evalRound)
            .compile
            .drain
        } >> S.supervise(selectPeers.foreverM).void

        private def recordFailure(pid: PeerId): F[Unit] =
          Clock[F].realTime.map(_.toMillis).flatMap { now =>
            gossipFailuresR.update { m =>
              val nextCount = m.get(pid).fold(1)(_.consecutiveFailures + 1).min(failureThreshold)
              val excludedUntil = Option.when(nextCount >= failureThreshold)(now + failureWindowMs)
              m.updated(pid, PeerFailureState(nextCount, excludedUntil))
            }
          }

        private def recordSuccess(pid: PeerId): F[Unit] =
          gossipFailuresR.update(_ - pid)

        private def peerSelectionState(activePeerIds: Set[PeerId]): F[PeerSelectionState] =
          Clock[F].realTime.map(_.toMillis).flatMap { now =>
            gossipFailuresR.modify { m =>
              val (updated, excluded, suspect) = m.iterator.filter { case (pid, _) => activePeerIds.contains(pid) }.foldLeft(
                (Map.empty[PeerId, PeerFailureState], Set.empty[PeerId], Set.empty[PeerId])
              ) {
                case ((states, excluded, suspect), (pid, state @ PeerFailureState(_, Some(until)))) if until > now =>
                  (states.updated(pid, state), excluded.incl(pid), suspect)
                case ((states, excluded, suspect), (pid, PeerFailureState(_, Some(_)))) =>
                  val halfOpen = PeerFailureState((failureThreshold - 1).max(0), None)
                  (states.updated(pid, halfOpen), excluded, suspect.incl(pid))
                case ((states, excluded, suspect), (pid, state)) =>
                  (states.updated(pid, state), excluded, suspect.incl(pid))
              }
              (updated, PeerSelectionState(excluded, suspect))
            }
          }

        private def evalRound(peer: Peer): F[Unit] =
          MonadCancel[F].guarantee(
            Temporal[F]
              .timed(round(peer))
              .flatMap {
                case (duration, _) => metrics.recordRoundDuration(duration, roundLabel)
              }
              .flatMap(_ => metrics.incrementGossipRoundSucceeded)
              .flatMap(_ => recordSuccess(peer.id))
              .handleErrorWith { err =>
                logger.error(s"Error running gossip round {peer=${peer.show}, reason=${err.show}") >>
                  recordFailure(peer.id) >> localHealthcheck.start(peer)
              },
            selectedPeersR.update(_.excl(peer))
          )

        private def enqueuePeer(peer: Peer, suspect: Boolean): F[Unit] =
          selectedPeersR.modify { selectedPeers =>
            if (selectedPeers.contains(peer))
              (selectedPeers, false)
            else
              (selectedPeers.incl(peer), true)
          }.ifM(
            (if (suspect) suspectPeersQueue else selectedPeersQueue)
              .tryOffer(peer)
              .ifM(Applicative[F].unit, selectedPeersR.update(_.excl(peer))),
            Applicative[F].unit
          )

        private def selectPeers: F[Unit] =
          for {
            _ <- Temporal[F].sleep(cfg.interval)
            knownPeers <- clusterStorage.getPeers
            knownPeerIds = knownPeers.iterator.map(_.id).toSet
            allPeers <- clusterStorage.getResponsivePeers
            peerState <- peerSelectionState(knownPeerIds)
            eligiblePeers =
              if (peerState.excluded.isEmpty) allPeers else allPeers.filterNot(p => peerState.excluded.contains(p.id))
            _ <- ExitOnFork.exitOnCheck("CL_EXIT_ON_FOLLOWER_GOSSIP", () => eligiblePeers.iterator.map(_.id).toSet)
            selectedPeers <- selectedPeersR.get
            availablePeers = eligiblePeers.diff(selectedPeers)
            drawnPeers <- Random[F].shuffleList(availablePeers.toList).map(_.take(cfg.fanout.value))
            _ <- drawnPeers.traverse(peer => enqueuePeer(peer, peerState.suspect.contains(peer.id)))
          } yield ()
      }
}
