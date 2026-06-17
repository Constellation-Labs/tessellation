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
      // Per-peer recent-failure timestamps (millis). A peer is excluded from this
      // runner's peer selection while it has at least `cfg.failureCountThreshold`
      // failure timestamps within `cfg.failureWindow` of the present. The 15s gossip
      // client timeout (application.conf:33) means each chronic-but-session-healthy
      // peer otherwise costs a full slot in `cfg.maxConcurrentRounds` every cycle, and
      // LocalHealthcheck restores it to Responsive as soon as `/session` succeeds.
      // This bypasses that loop without changing the healthcheck semantics for other
      // callers. State is per-runner so peer-round and common-round track independently.
      gossipFailuresR <- Ref.of(Map.empty[PeerId, Vector[Long]])
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
        } >> S.supervise(selectPeers.foreverM).void

        /** Peers in transient states where gossip failures are expected and should not be logged at ERROR level. */
        private val transientStates: Set[NodeState] =
          Set(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.WaitingForObserving, NodeState.Leaving)

        private def recordFailure(pid: PeerId): F[Unit] =
          Clock[F].realTime.map(_.toMillis).flatMap { now =>
            val cutoff = now - failureWindowMs
            gossipFailuresR.update { m =>
              val prior = m.getOrElse(pid, Vector.empty[Long]).filter(_ >= cutoff)
              // Cap retained timestamps at threshold; we only care whether we hit it,
              // not how far past we are, so this also bounds the per-peer memory.
              val updated = (prior :+ now).takeRight(failureThreshold)
              m.updated(pid, updated)
            }
          }

        private def recordSuccess(pid: PeerId): F[Unit] =
          gossipFailuresR.update(_ - pid)

        private def excludedPeerIds: F[Set[PeerId]] =
          Clock[F].realTime.map(_.toMillis).flatMap { now =>
            val cutoff = now - failureWindowMs
            gossipFailuresR.modify { m =>
              // Prune timestamps that aged out of the window. Peers whose surviving
              // list is below threshold are dropped from the map entirely so memory
              // does not accumulate. The set of excluded peers is what's returned.
              val pruned = m.flatMap {
                case (pid, ts) =>
                  val live = ts.filter(_ >= cutoff)
                  if (live.isEmpty) None else Some(pid -> live)
              }
              val excluded = pruned.collect { case (pid, ts) if ts.length >= failureThreshold => pid }.toSet
              (pruned, excluded)
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

        private def recordPeerSelectionSnapshot(allPeers: Set[Peer], excluded: Set[PeerId]): F[Unit] = {
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

          stateGauges >> excludedGauges
        }

        private def selectPeers: F[Unit] =
          for {
            _ <- Temporal[F].sleep(cfg.interval)
            allPeers <- clusterStorage.getResponsivePeers
            excluded <- excludedPeerIds
            _ <- recordPeerSelectionSnapshot(allPeers, excluded)
            eligiblePeers = if (excluded.isEmpty) allPeers else allPeers.filterNot(p => excluded.contains(p.id))
            _ <- ExitOnFork.exitOnCheck("CL_EXIT_ON_FOLLOWER_GOSSIP", () => eligiblePeers.map(_.id))
            selectedPeers <- selectedPeersR.get
            availablePeers = eligiblePeers.diff(selectedPeers)
            drawnPeers <- Random[F].shuffleList(availablePeers.toList).map(_.take(cfg.fanout.value))
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
