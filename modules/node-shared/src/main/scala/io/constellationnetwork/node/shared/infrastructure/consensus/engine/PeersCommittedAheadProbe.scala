package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.effect.Async
import cats.effect.std.Random
import cats.effect.syntax.all._
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.Peer
import io.constellationnetwork.schema.snapshot.SnapshotMetadata

/** HTTP preflight for the AbandonmentTracker's recovery escalation (issue #1533).
  *
  * Frozen rumor state cannot prove anything about cluster progress: an isolated node's `peerCurrentKeys` map may be frozen strictly below
  * the abandoned key, frozen AT it (a single pre-isolation declaration pins the monotone-max entry with no freshness), or empty
  * (`clearAllPeerRegistrations` wipes it during recovery) -- and the exact same three shapes occur on every node of a cluster that stalled
  * together, where escalating would cascade everyone into WaitingForDownload with nothing to serve (the historical false-lagging cascade
  * recorded in StallDetector's lagging-detection comment). This probe supplies the discriminating ground truth over HTTP, which an
  * isolated-but-reachable node still has: ask a bounded random sample of Ready responsive peers for their latest committed snapshot
  * metadata. Confirmation requires a strict majority of responders agreeing on the same ahead `(ordinal, hash)`, with at least
  * `minCorroborators` matching peers on any cluster large enough to provide them (clamped to the sample size so a two-node metagraph's
  * single peer can still confirm) -- so on real clusters one faulty or Byzantine scalar response cannot drain validators into recovery. In
  * a genuine cluster-wide stall nobody can corroborate the abandoned key and every node keeps retrying.
  *
  * Failure-safe by construction: per-peer errors and timeouts drop that peer (a non-Ready peer 503s on the endpoint), and an overall
  * timeout or any other error returns an explicit non-confirming outcome. Degraded probes suppress recovery, never trigger it.
  *
  * The constants are local-liveness tuning (release-version fenced, not consensus-agreed): a sample of 8 bounds fan-out on large clusters
  * while making a false-negative on a genuinely-advanced network vanishingly unlikely across repeated abandonment cycles; parallelism and
  * timeout mirror the DownloadDaemon/PeerSelect probe posture. The advertised jar hash is not a handshake fence.
  */
object PeersCommittedAheadProbe {

  val SampleSize: Int = 8
  val Parallelism: Int = 4
  val MinCorroborators: Int = 2
  val PerPeerTimeout: FiniteDuration = 2.seconds
  val OverallTimeout: FiniteDuration = 10.seconds

  /** Production wiring: Ready responsive peers from `clusterStorage`, defaults for the tuning knobs. The layers supply
    * `fetchLatestCommittedMetadata` against their own snapshot endpoint.
    */
  def make[F[_]: Async: Random](
    clusterStorage: ClusterStorage[F],
    fetchLatestCommittedMetadata: Peer => F[SnapshotMetadata]
  ): SnapshotOrdinal => F[AbandonmentTracker.PeersAheadProbe] = { abandonedKey =>
    clusterStorage.getResponsivePeers.flatMap { peers =>
      val readyPeers = peers.iterator.filter(_.state === NodeState.Ready).toList
      probe(readyPeers, fetchLatestCommittedMetadata, abandonedKey)
    }
  }

  /** Effectful core, parameterized for direct testing: sample, fan out, tolerate per-peer failures, compare committed ordinals against the
    * abandoned key, and never let a degraded probe read as confirmation.
    */
  def probe[F[_]: Async: Random](
    readyPeers: List[Peer],
    fetchLatestCommittedMetadata: Peer => F[SnapshotMetadata],
    abandonedKey: SnapshotOrdinal,
    sampleSize: Int = SampleSize,
    parallelism: Int = Parallelism,
    minCorroborators: Int = MinCorroborators,
    perPeerTimeout: FiniteDuration = PerPeerTimeout,
    overallTimeout: FiniteDuration = OverallTimeout
  ): F[AbandonmentTracker.PeersAheadProbe] =
    Random[F]
      .shuffleList(readyPeers)
      .map(_.take(sampleSize))
      .flatMap { sample =>
        sample
          .parTraverseN(math.max(1, parallelism)) { peer =>
            fetchLatestCommittedMetadata(peer)
              .map[PeerResult](PeerResult.Responded(_))
              .timeoutTo(perPeerTimeout, (PeerResult.TimedOut: PeerResult).pure[F])
              .handleError(_ => PeerResult.Errored)
          }
          .map(results => summarize(results, sample.size, abandonedKey, minCorroborators))
      }
      .timeoutTo(overallTimeout, AbandonmentTracker.PeersAheadProbe.timedOut.pure[F])
      .handleError(_ => AbandonmentTracker.PeersAheadProbe.failed)

  /** Per-peer local result of one sampled fetch. Retained as counts on `PeersAheadProbe` so the escalation paths can explain a
    * non-confirming probe (`AbandonmentTracker.SuppressedBy`) without changing how it is decided.
    */
  sealed trait PeerResult
  object PeerResult {
    final case class Responded(metadata: SnapshotMetadata) extends PeerResult
    case object TimedOut extends PeerResult
    case object Errored extends PeerResult
  }

  /** Pure fold of the sampled results into the probe verdict plus its reason data. The confirmation formula is unchanged: a strict
    * responder-majority agreeing on one `(ordinal, hash)` at/above the key, with at least `minCorroborators` matching peers clamped to the
    * sample size. The extra counts and the corroborating group's identity are telemetry only.
    */
  private[engine] def summarize(
    results: List[PeerResult],
    sampledPeers: Int,
    abandonedKey: SnapshotOrdinal,
    minCorroborators: Int
  ): AbandonmentTracker.PeersAheadProbe = {
    val responded = results.collect { case PeerResult.Responded(metadata) => metadata }
    val timedOut = results.count { case PeerResult.TimedOut => true; case _ => false }
    val errored = results.count { case PeerResult.Errored => true; case _ => false }
    val belowKey = responded.count(_.ordinal < abandonedKey)
    val atKey = responded.count(_.ordinal === abandonedKey)
    val aboveKey = responded.count(_.ordinal > abandonedKey)
    val atOrAbove = responded.filter(metadata => metadata.ordinal >= abandonedKey)
    val aheadGroups = atOrAbove.groupBy(metadata => (metadata.ordinal, metadata.hash))
    // Deterministic pick of the corroborating group: largest, then highest ordinal, then hash text.
    val corroboratingGroup = aheadGroups.toList.map { case ((ordinal, hash), members) => (members.size, ordinal.value.value, hash.value) }
      .sorted(Ordering.Tuple3(Ordering[Int].reverse, Ordering[Long].reverse, Ordering[String]))
      .headOption
    val corroboratingPeers = corroboratingGroup.fold(0)(_._1)
    val corroboratingOrdinal = corroboratingGroup.map(_._2)
    val hashesAtCorroboratingOrdinal = corroboratingOrdinal.fold(0) { ordinal =>
      atOrAbove.filter(_.ordinal.value.value == ordinal).map(_.hash).distinct.size
    }
    // The corroboration requirement scales down to what the cluster can possibly provide:
    // demanding two matching responses inside a two-node metagraph (sample of one peer)
    // would re-open #1533 as a permanent small-cluster suppression. With a single sampled
    // peer, that peer is the only possible download source anyway, and the download stays
    // signature-validated and checkpoint-gated -- this probe is a liveness heuristic, not
    // a trust decision (the PeerSelect posture). A temporarily shrunken sample (e.g. a
    // three-node cluster with one peer down) self-heals: the dead peer drops out of the
    // Ready responsive set and the requirement follows it down on later abandonment
    // cycles. On any cluster that can provide `minCorroborators` peers, the full
    // requirement stands.
    val requiredCorroborators = math.max(1, math.min(minCorroborators, sampledPeers))
    val confirmedAhead =
      corroboratingPeers >= requiredCorroborators && corroboratingPeers * 2 > responded.size
    AbandonmentTracker.PeersAheadProbe(
      confirmedAhead = confirmedAhead,
      probedPeers = sampledPeers,
      respondedPeers = responded.size,
      corroboratingPeers = corroboratingPeers,
      outcome = AbandonmentTracker.ProbeOutcome.Completed,
      timedOutPeers = timedOut,
      erroredPeers = errored,
      belowKeyPeers = belowKey,
      atKeyPeers = atKey,
      aboveKeyPeers = aboveKey,
      requiredCorroborators = requiredCorroborators,
      aheadGroups = aheadGroups.size,
      corroboratingOrdinal = corroboratingOrdinal,
      hashesAtCorroboratingOrdinal = hashesAtCorroboratingOrdinal
    )
  }
}
