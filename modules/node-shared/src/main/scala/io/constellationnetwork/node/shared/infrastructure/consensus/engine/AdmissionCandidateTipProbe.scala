package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.kernel.Async
import cats.effect.syntax.all._
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.AdmissionNomineeSelector
import io.constellationnetwork.node.shared.infrastructure.gossip.event.ChainTip
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.schema.snapshot.SnapshotMetadata
import io.constellationnetwork.security.hash.Hash

/** Local readiness evidence for one fixed admission target.
  *
  * Open admission reuses the authenticated lightweight snapshot-metadata request already used by recovery preflights. Probation recovery
  * reuses the existing IHave chain-tip payload/source through a chain-tip-only request, so a peer intentionally held before Ready can still
  * prove its downloaded parent. The observation can only make this node emit (or withhold) an admission vote; an AdmissionCertificate
  * remains the membership authority. No response is fail-closed.
  */
object AdmissionCandidateTipProbe {

  val PerPeerTimeout: FiniteDuration = 1.second
  val MinimumProbationProbeInterval: FiniteDuration = 1.second

  /** Distinguishes a deliberately throttled tick from a launched probe that failed closed.
    *
    * This distinction is load-bearing for probation hysteresis: throttling preserves the prior streak, while a timeout, transport failure,
    * stale tip, or conflicting tip resets it.
    */
  private[consensus] sealed trait Observation extends Product with Serializable
  private[consensus] object Observation {
    case object NotAttempted extends Observation
    final case class Attempted(tip: Option[ChainTip]) extends Observation
  }

  /** Local-only probe lanes keep the open and recovery eligibility contracts distinct.
    *
    * Open expansion may inspect only a peer already advertised as Ready. A peer carried in probation cannot reach Ready until a certificate
    * clears that probation, so its recovery lane may inspect the pre-Ready states that can already answer the authenticated chain-tip
    * endpoint. The exact-parent check remains outside this transport helper.
    */
  sealed trait Lane extends Product with Serializable {
    private[consensus] def accepts(state: NodeState): Boolean
    private[consensus] def label: String
    private[consensus] def isProbationRecovery: Boolean
  }

  object Lane {
    case object OpenReady extends Lane {
      private[consensus] def accepts(state: NodeState): Boolean = state === NodeState.Ready
      private[consensus] val label: String = "open"
      private[consensus] val isProbationRecovery: Boolean = false
    }

    case object ProbationRecovery extends Lane {
      private[consensus] def accepts(state: NodeState): Boolean =
        state === NodeState.Observing || state === NodeState.WaitingForReady || state === NodeState.Ready
      private[consensus] val label: String = "probation"
      private[consensus] val isProbationRecovery: Boolean = true
    }
  }

  final case class Probes[F[_]](
    open: PeerId => F[Option[ChainTip]],
    probation: PeerId => F[Option[ChainTip]]
  )

  private[consensus] def isProbeDue(
    lastAttempt: Option[FiniteDuration],
    now: FiniteDuration,
    minimumInterval: FiniteDuration
  ): Boolean =
    lastAttempt.forall(last => now - last >= minimumInterval)

  /** Execute at most one fixed target in each lane, concurrently and independently.
    *
    * Keeping this orchestration in the typed probe boundary prevents an unavailable probation target from suppressing the open lane while
    * preserving the no-target-walking invariant within each lane.
    */
  private[consensus] def runLaneProbes[F[_]: Async](
    probes: Probes[F],
    probationTarget: Option[PeerId],
    openTarget: Option[PeerId]
  ): F[List[(PeerId, Lane, Option[ChainTip])]] = {
    def execute(
      target: Option[PeerId],
      lane: Lane,
      probe: PeerId => F[Option[ChainTip]]
    ): F[Option[(PeerId, Lane, Option[ChainTip])]] =
      target.traverse(pid => probe(pid).map(tip => (pid, lane, tip)))

    Async[F]
      .both(
        execute(probationTarget, Lane.ProbationRecovery, probes.probation),
        execute(openTarget, Lane.OpenReady, probes.open)
      )
      .map { case (probation, open) => List(probation, open).flatten }
  }

  /** Choose at most the fixed first open target, and never retry it within one continuous monitor attempt.
    *
    * Cached gossip is deliberately not consulted here. When the direct-probe lane is installed, only a fresh response from the fixed target
    * may authorize an open admission vote. In particular, a bounded-lag or wrong-hash cache entry must neither suppress the request nor
    * become vote evidence after a failed request.
    */
  private[consensus] def targetForRound(
    openAdmissionTargets: List[PeerId],
    probedTargets: Set[PeerId]
  ): Option[PeerId] =
    openAdmissionTargets.headOption.filterNot(probedTargets.contains)

  /** Resolve open ReadyAtTip vote evidence without mixing freshness domains.
    *
    * Global L0 installs direct probes and therefore requires a fresh exact response from the open lane in this monitor attempt. Currency L0
    * installs no probe and retains the legacy cached-gossip predicate byte-for-behavior. The explicit branch prevents a failed or
    * conflicting direct response from falling back to a cached tip during the same decision.
    */
  private[consensus] def readyOpenTargets(
    openAdmissionTargets: List[PeerId],
    cachedChainTips: Map[PeerId, ChainTip],
    directProbeResults: List[(PeerId, Lane, Option[ChainTip])],
    directProbesEnabled: Boolean,
    expectedHash: Hash,
    expectedOrdinal: Option[SnapshotOrdinal],
    cachedTipIsReady: ChainTip => Boolean
  ): List[PeerId] =
    if (directProbesEnabled)
      openAdmissionTargets.filter { target =>
        directProbeResults.exists {
          case (`target`, Lane.OpenReady, Some(tip)) => AdmissionTipReadiness.isExact(tip, expectedHash, expectedOrdinal)
          case _                                     => false
        }
      }
    else
      openAdmissionTargets.filter(target => cachedChainTips.get(target).exists(cachedTipIsReady))

  /** Pick one fixed probation target for the whole round. The parent hash is round-stable entropy, so every monitor tick selects the same
    * target and a failed probe cannot walk the candidate set.
    */
  private[consensus] def probationTargetForRound(
    probation: Set[PeerId],
    entropy: Hash
  ): Option[PeerId] =
    AdmissionNomineeSelector.select(probation, Set.empty, entropy)

  /** Advance the fixed probation target's streak only from a fresh exact direct observation. A failed, stale, or conflicting response
    * resets it. Other targets are deliberately dropped: this bridge audits one target at a time and cannot accumulate stale evidence across
    * targets.
    */
  private[consensus] def updateExactProbationStreak(
    previous: Map[PeerId, Int],
    target: Option[PeerId],
    observation: Observation,
    expectedHash: Hash,
    expectedOrdinal: Option[SnapshotOrdinal]
  ): Map[PeerId, Int] =
    target.fold(Map.empty[PeerId, Int]) { pid =>
      observation match {
        case Observation.NotAttempted => previous.get(pid).fold(Map.empty[PeerId, Int])(count => Map(pid -> count))
        case Observation.Attempted(observedTip) =>
          val next =
            if (observedTip.exists(AdmissionTipReadiness.isExact(_, expectedHash, expectedOrdinal))) previous.getOrElse(pid, 0) + 1
            else 0
          Map(pid -> next)
      }
    }

  /** A carried streak alone is never vote evidence. The threshold must be reached by a fresh, exact response launched on this tick;
    * throttled ticks can preserve history only.
    */
  private[consensus] def readyProbationTarget(
    target: Option[PeerId],
    observation: Observation,
    streaks: Map[PeerId, Int],
    minimumStreak: Int,
    alreadyVotedBySelf: Set[PeerId],
    expectedHash: Hash,
    expectedOrdinal: Option[SnapshotOrdinal]
  ): Set[PeerId] = {
    val freshExact = observation match {
      case Observation.Attempted(Some(tip)) => AdmissionTipReadiness.isExact(tip, expectedHash, expectedOrdinal)
      case _                                => false
    }

    target.filter { pid =>
      freshExact &&
      !alreadyVotedBySelf.contains(pid) &&
      streaks.getOrElse(pid, 0) >= math.max(1, minimumStreak)
    }.toSet
  }

  /** Merge a direct response only when it names the exact expected parent. */
  private[consensus] def mergeExactResult(
    cachedChainTips: Map[PeerId, ChainTip],
    result: Option[(PeerId, Option[ChainTip])],
    expectedHash: Hash,
    expectedOrdinal: Option[SnapshotOrdinal]
  ): Map[PeerId, ChainTip] =
    result match {
      case Some((target, Some(tip))) if AdmissionTipReadiness.isExact(tip, expectedHash, expectedOrdinal) =>
        cachedChainTips.updated(target, tip)
      case _ => cachedChainTips
    }

  def make[F[_]: Async](
    clusterStorage: ClusterStorage[F],
    fetchLatestCommittedMetadata: Peer => F[SnapshotMetadata],
    fetchProbationChainTip: Peer => F[Option[ChainTip]]
  ): Probes[F] = {
    val fetchOpenChainTip: Peer => F[Option[ChainTip]] = peer =>
      fetchLatestCommittedMetadata(peer).map(metadata => ChainTip(metadata.ordinal, metadata.hash).some)

    def forLane(
      lane: Lane,
      fetchChainTip: Peer => F[Option[ChainTip]]
    ): PeerId => F[Option[ChainTip]] = target =>
      clusterStorage.getResponsivePeers.flatMap { peers =>
        probe(peers, target, lane, fetchChainTip)
      }

    Probes(
      open = forLane(Lane.OpenReady, fetchOpenChainTip),
      probation = forLane(Lane.ProbationRecovery, fetchProbationChainTip)
    )
  }

  private[consensus] def probe[F[_]: Async](
    responsivePeers: Set[Peer],
    target: PeerId,
    lane: Lane,
    fetchChainTip: Peer => F[Option[ChainTip]],
    timeout: FiniteDuration = PerPeerTimeout
  ): F[Option[ChainTip]] =
    responsivePeers
      .find(peer => peer.id === target && lane.accepts(peer.state))
      .fold(none[ChainTip].pure[F]) { peer =>
        fetchChainTip(peer)
          .timeoutTo(timeout, none[ChainTip].pure[F])
          .handleError(_ => none[ChainTip])
      }
}

/** Exact interpretation of a fresh, direct candidate response.
  *
  * Cached gossip tips intentionally retain rc.6 behavior in [[StallDetector]]. A direct response is contemporaneous, so it becomes vote
  * evidence only when both ordinal and hash name the expected parent.
  */
object AdmissionTipReadiness {

  val OrdinalLagTolerance: Long = 2L

  def isExact(tip: ChainTip, expectedHash: Hash, expectedOrdinal: Option[SnapshotOrdinal]): Boolean =
    expectedOrdinal.fold(tip.snapshotHash === expectedHash) { ordinal =>
      tip.ordinal === ordinal && tip.snapshotHash === expectedHash
    }

  /** Interpret asynchronously sampled gossip without leaking Global L0's certified policy into Currency L0. Certified atomic membership
    * requires the exact parent; legacy layers retain the existing bounded-lag behavior byte-for-behavior.
    */
  def isCachedReady(
    tip: ChainTip,
    expectedHash: Hash,
    expectedOrdinal: Option[SnapshotOrdinal],
    requireExact: Boolean
  ): Boolean =
    if (requireExact) isExact(tip, expectedHash, expectedOrdinal)
    else
      tip.snapshotHash === expectedHash ||
      expectedOrdinal.exists(ordinal => tip.ordinal.value.value + OrdinalLagTolerance >= ordinal.value.value)
}
