package io.constellationnetwork.node.shared.infrastructure.gossip

import cats.Applicative
import cats.effect.std.{Queue, Random, Supervisor}
import cats.effect.{metrics => _, _}
import cats.syntax.all._

import io.constellationnetwork.node.shared.config.types.GossipRoundConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.infrastructure.fork.ExitOnFork
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.errorShow
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId}

import eu.timepit.refined.auto._
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
      // A first failure moves a peer to a separate, bounded suspect lane. Consecutive
      // failures open its circuit for `failureWindow`, regardless of how long each
      // failed request took. A successful round clears the state. This prevents slow
      // response bodies from occupying the healthy-peer pool indefinitely.
      gossipFailuresR <- Ref.of(Map.empty[PeerId, PeerFailureState])
    } yield
      new GossipRoundRunner[F] {
        private val logger = Slf4jLogger.getLogger[F]
        private val failureWindowMs = cfg.failureWindow.toMillis
        private val failureThreshold = cfg.failureCountThreshold.value
        private val peerIdLabel = Metrics.unsafeLabelName("peer_id")
        private val peerStateLabel = Metrics.unsafeLabelName("peer_state")
        private val reasonLabel = Metrics.unsafeLabelName("reason")
        private val runnerLabel = Metrics.unsafeLabelName("runner")

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

        /** Peers in transient states where gossip failures are expected and should not be logged at ERROR level. */
        private val transientStates: Set[NodeState] =
          Set(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.WaitingForObserving, NodeState.Leaving)

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
                  // Half-open after cooldown: one trial stays in the suspect lane.
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
              .flatMap(_ => recordPeerRoundSuccess(peer))
              .flatMap(_ => recordSuccess(peer.id))
              .handleErrorWith { err =>
                val logEffect =
                  if (transientStates.contains(peer.state))
                    logger.debug(s"Gossip round failed for peer in ${peer.state} {peer=${peer.show}, reason=${err.show}}")
                  else
                    logger.warn(s"Error running gossip round {peer=${peer.show}, reason=${err.show}}")
                logEffect >> recordPeerRoundFailure(peer, err) >> recordFailure(peer.id) >> localHealthcheck.start(peer)
              },
            selectedPeersR.update(_.excl(peer))
          )

        private def peerTags(peer: Peer): Metrics.TagSeq =
          Seq(
            peerIdLabel -> peer.id.value.value.take(8),
            peerStateLabel -> peer.state.entryName,
            runnerLabel -> roundLabel
          )

        private def recordPeerRoundSuccess(peer: Peer): F[Unit] =
          Metrics[F].incrementCounter("dag_gossip_peer_round_success_total", peerTags(peer))

        private def recordPeerRoundFailure(peer: Peer, err: Throwable): F[Unit] =
          Metrics[F].incrementCounter(
            "dag_gossip_peer_round_failure_total",
            peerTags(peer) :+ (reasonLabel -> err.getClass.getSimpleName)
          )

        private def recordPeerSelectionSnapshot(
          allPeers: Set[Peer],
          excluded: Set[PeerId],
          suspect: Set[PeerId]
        ): F[Unit] = {
          val countsByState = allPeers.groupMapReduce(_.state)(_ => 1)(_ + _)
          val stateGauges =
            NodeState.values.toList.traverse_ { state =>
              Metrics[F].updateGauge(
                "dag_gossip_responsive_peer_state_count",
                countsByState.getOrElse(state, 0).toLong,
                Seq(peerStateLabel -> state.entryName, runnerLabel -> roundLabel)
              )
            }

          val excludedByState = allPeers.filter(peer => excluded.contains(peer.id)).groupMapReduce(_.state)(_ => 1)(_ + _)
          val excludedGauges =
            NodeState.values.toList.traverse_ { state =>
              Metrics[F].updateGauge(
                "dag_gossip_excluded_peer_state_count",
                excludedByState.getOrElse(state, 0).toLong,
                Seq(peerStateLabel -> state.entryName, runnerLabel -> roundLabel)
              )
            }

          val suspectByState = allPeers.filter(peer => suspect.contains(peer.id)).groupMapReduce(_.state)(_ => 1)(_ + _)
          val suspectGauges =
            NodeState.values.toList.traverse_ { state =>
              Metrics[F].updateGauge(
                "dag_gossip_suspect_peer_state_count",
                suspectByState.getOrElse(state, 0).toLong,
                Seq(peerStateLabel -> state.entryName, runnerLabel -> roundLabel)
              )
            }

          stateGauges >> excludedGauges >> suspectGauges
        }

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
            _ <- recordPeerSelectionSnapshot(allPeers, peerState.excluded, peerState.suspect)
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
