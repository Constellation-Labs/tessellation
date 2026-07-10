package io.constellationnetwork.node.shared.infrastructure.gossip.event

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Queries a peer for the snapshot hash at a given ordinal.
  *
  * Returns:
  *   - `Some(hash)` if the peer has a snapshot at that ordinal
  *   - `None` if the peer doesn't have that ordinal OR the RPC fails (treated as inconclusive)
  *
  * This is the primitive used by Tier 2 fork detection: the (ordinal, hash) tuple uniquely identifies a snapshot. If my localHash matches
  * the peer's hash at my localOrdinal, we're on the same chain — I'm just lagging. If the hashes differ, we're on different chains at that
  * ordinal — a real fork.
  */
trait HashAtOrdinalProbe[F[_]] {
  def probe(peerId: PeerId, ordinal: SnapshotOrdinal): F[Option[Hash]]
}

/** Information about a detected fork divergence.
  *
  * @param majorityOrdinal
  *   The ordinal that the majority of peers agree on
  * @param majorityHash
  *   The snapshot hash at the majority ordinal
  * @param majorityPeers
  *   The set of peers on the majority chain
  * @param localOrdinal
  *   This node's current ordinal
  * @param lag
  *   How far behind the majority this node is (negative if local is ahead on a fork)
  */
case class ForkRecoveryInfo(
  majorityOrdinal: SnapshotOrdinal,
  majorityHash: Hash,
  majorityPeers: Set[PeerId],
  localOrdinal: SnapshotOrdinal,
  lag: Long
)

/** Detects fork divergence by comparing local chain tip against peer chain tips collected via gossip.
  *
  * Fork detection uses (ordinal, hash) pairs — not ordinal alone. Two scenarios:
  *
  *   1. **Lagging fork**: Local ordinal is behind the majority. The node fell off the main chain and stopped advancing. Detected when: lag
  *      > forkLagThreshold AND majority > 50% of reporters.
  *
  * 2. **Running fork**: Local ordinal matches the majority, but with a DIFFERENT hash. The node is on a parallel chain producing its own
  * snapshots (e.g., a 2-node mini-fork after partition). Detected when: peers at the same ordinal have a different hash AND those peers
  * form a majority.
  *
  * We intentionally do NOT detect "minority fork" (local ahead of majority) via hash comparison, because different ordinals always have
  * different hashes regardless of fork status. A node 1 ordinal ahead is normal during round completion. If a minority-fork node is truly
  * stuck, the stale-ordinal escalation in AbandonmentTracker handles it as a safety net.
  *
  * The majority threshold uses strict majority (> 50% of reporting peers). This is intentional: with 4+ nodes required for quorum, > 50% of
  * chain tip reporters is a reliable signal that the node has diverged.
  */
trait ForkRecoveryDetector[F[_]] {
  def detectForkDivergence: F[Option[ForkRecoveryInfo]]
}

object ForkRecoveryDetector {

  /** Default number of peers to probe in Tier 2 quorum verification. Odd so majority is unambiguous. */
  val DefaultProbeCount: Int = 3

  /** Default per-peer probe timeout. A slow/firewalled peer (e.g. NATed community node reachable only via CloudFront) would otherwise hang
    * the parTraverse and block the whole Tier 2 decision. On timeout we count the peer as `absent` — existing logic treats absent responses
    * as inconclusive and defers to the next detector cycle.
    *
    * Timeout must be generous enough that a healthy-but-busy peer under contention still responds (observed Apr 18 E2E: gl0-1 detected a
    * real fork but Tier-2 produced `match=0 mismatch=0 absent=3` because 3s was too tight under 5-JVM docker-compose load — all three
    * probed peers timed out, leaving the fork undetected). 10s gives peers breathing room while still preventing fiber accumulation from
    * genuinely unreachable targets.
    */
  val DefaultProbeTimeout: FiniteDuration = 10.seconds

  def make[F[_]: Async: Parallel](
    meshState: MeshState[F],
    getLocalChainTip: F[Option[ChainTip]],
    forkLagThreshold: Long = 10,
    verifyHashAt: Option[HashAtOrdinalProbe[F]] = None,
    probeCount: Int = DefaultProbeCount,
    probeTimeout: FiniteDuration = DefaultProbeTimeout
  ): ForkRecoveryDetector[F] = new ForkRecoveryDetector[F] {

    private val logger = Slf4jLogger.getLogger[F]

    def detectForkDivergence: F[Option[ForkRecoveryInfo]] =
      for {
        chainTips <- meshState.getChainTips
        localTipOpt <- getLocalChainTip
        result <- (localTipOpt, chainTips.nonEmpty).pure[F].flatMap {
          case (Some(localTip), true) =>
            val localOrdinal = localTip.ordinal
            val localHash = localTip.snapshotHash

            // Group peers by (ordinal, hash) — the full chain tip identity
            val tipGroups: Map[(SnapshotOrdinal, Hash), Map[PeerId, ChainTip]] =
              chainTips.groupBy { case (_, tip) => (tip.ordinal, tip.snapshotHash) }

            // Find the largest group — the majority chain
            val ((majorityOrdinal, majorityHash), majorityGroup) = tipGroups.maxBy(_._2.size)
            val isMajority = majorityGroup.size > chainTips.size / 2

            if (!isMajority) {
              // No clear majority — can't determine which chain is canonical
              none[ForkRecoveryInfo].pure[F]
            } else {
              val lag = majorityOrdinal.value.value - localOrdinal.value.value

              // Check 1: Lagging fork — local is far behind the majority
              val isLagging = lag > forkLagThreshold

              // Check 2: Running fork — same ordinal but different hash.
              // Find peers at our ordinal: if a majority of them have a different hash, we're forked.
              val peersAtLocalOrdinal = chainTips.filter { case (_, tip) => tip.ordinal == localOrdinal }
              val peersWithDifferentHash = peersAtLocalOrdinal.filter { case (_, tip) => tip.snapshotHash != localHash }
              // Intentionally conservative: requires strict majority (> 50%) of peers at our ordinal
              // to disagree on hash. With 2 peers, both must disagree (unanimity). This avoids
              // false positives from temporary network jitter at the cost of slower detection
              // for 2-node mini-forks. The lagging fork check (forkLagThreshold) catches those
              // cases once the mini-fork falls behind the canonical chain.
              val isRunningFork = peersAtLocalOrdinal.size >= 2 && peersWithDifferentHash.size > peersAtLocalOrdinal.size / 2

              // NOTE: We intentionally do NOT check "local ahead of majority" (minority fork).
              // Hash comparison across different ordinals is meaningless — ordinal 11's hash will
              // never equal ordinal 10's hash regardless of fork status. A node 1 ordinal ahead
              // is normal (it finished the round first). If a minority-fork node is truly stuck,
              // the stale-ordinal escalation in AbandonmentTracker handles it (retriable
              // abandonments at the same key → escalate after maxRetriableAtSameKey attempts).

              if (isLagging || isRunningFork) {
                val reason =
                  if (isRunningFork && !isLagging)
                    s"hash_divergence local=($localOrdinal,$localHash) vs majority=($majorityOrdinal,$majorityHash) " +
                      s"peersAtOrdinal=${peersAtLocalOrdinal.size} disagree=${peersWithDifferentHash.size}"
                  else
                    s"ordinal_lag local=${localOrdinal.value.value} majority=${majorityOrdinal.value.value} lag=$lag"

                val info = ForkRecoveryInfo(
                  majorityOrdinal = majorityOrdinal,
                  majorityHash = majorityHash,
                  majorityPeers = majorityGroup.keySet,
                  localOrdinal = localOrdinal,
                  lag = lag
                )
                logger
                  .warn(
                    s"Fork divergence detected: $reason " +
                      s"majorityPeers=${majorityGroup.size}/${chainTips.size}"
                  )
                  .as(info.some)
              } else {
                // Tier 2: ambiguous case. Local is alone on its tip and majority is ahead but
                // within forkLagThreshold. Could be either:
                //   a) legitimately lagging on the canonical chain (peers have my localHash at my
                //      localOrdinal in their history)
                //   b) isolated on a minority fork (peers have a DIFFERENT hash at my localOrdinal)
                //
                // From chain tips alone this is undecidable — but we can directly verify by
                // probing majority peers: "what snapshot hash do YOU have at my ordinal?"
                // (ordinal, hash) tuples uniquely identify snapshots, so the response answers
                // the question definitively.
                val ambiguous =
                  peersAtLocalOrdinal.isEmpty && lag > 0 && majorityGroup.size >= 2
                if (ambiguous && verifyHashAt.isDefined) {
                  runQuorumProbe(
                    verifyHashAt.get,
                    majorityGroup.keySet,
                    localOrdinal,
                    localHash,
                    majorityOrdinal,
                    majorityHash,
                    chainTipsSize = chainTips.size,
                    lag = lag,
                    majorityPeers = majorityGroup.keySet
                  )
                } else {
                  none[ForkRecoveryInfo].pure[F]
                }
              }
            }
          case _ => none[ForkRecoveryInfo].pure[F]
        }
      } yield result

    /** Tier 2 fork verification via hash-at-ordinal probing.
      *
      * Pre-condition (checked by caller): local is alone on its tip AND majority is ahead AND Tier 1 did not fire. We cannot tell from
      * chain-tip data alone whether we're lagging on the canonical chain or isolated on a minority fork.
      *
      * Algorithm:
      *   1. Sample up to `probeCount` peers from the majority group (those claiming the canonical tip). 2. Probe each in parallel: "what
      *      hash do you have at MY localOrdinal?" 3. Classify responses into match/mismatch/absent buckets. 4. Decide:
      *      - match >= majority of (match+mismatch): SAME CHAIN → no fork
      *      - mismatch with quorum agreement on a single hash: FORK CONFIRMED → return info
      *      - otherwise (inconclusive): no action, retry next cycle
      *
      * BFT property: requires a majority of responding peers to agree. A minority of malicious/stale responders cannot flip the decision.
      */
    private def runQuorumProbe(
      probe: HashAtOrdinalProbe[F],
      candidatePeers: Set[PeerId],
      localOrdinal: SnapshotOrdinal,
      localHash: Hash,
      majorityOrdinal: SnapshotOrdinal,
      majorityHash: Hash,
      chainTipsSize: Int,
      lag: Long,
      majorityPeers: Set[PeerId]
    ): F[Option[ForkRecoveryInfo]] = {
      val sample = candidatePeers.toList.take(probeCount)
      sample.parTraverse { peerId =>
        // Per-peer timeout: a slow peer must not block the parTraverse. On timeout the
        // response is None ("absent"), which the classifier treats as inconclusive.
        Async[F]
          .timeout(probe.probe(peerId, localOrdinal), probeTimeout)
          .attempt
          .map(_.toOption.flatten)
      }.flatMap { responses =>
        val present = responses.flatten
        val matchCount = present.count(_ === localHash)
        val mismatchHashes = present.filter(_ =!= localHash)
        val mismatchCount = mismatchHashes.size
        val totalResponses = matchCount + mismatchCount
        val absentCount = responses.size - totalResponses

        // Need at least 2 responses to form a quorum decision (avoid acting on a single voice).
        val hasEnoughResponses = totalResponses >= 2
        val matchWins = hasEnoughResponses && matchCount > totalResponses / 2
        val mismatchWins =
          hasEnoughResponses && mismatchCount > totalResponses / 2 && {
            // Within mismatch group, require majority to agree on SAME divergent hash.
            // Mixed-hash mismatches indicate a fractured cluster — inconclusive.
            val grouped = mismatchHashes.groupBy(identity).view.mapValues(_.size).toMap
            val topCount = if (grouped.isEmpty) 0 else grouped.values.max
            topCount > mismatchCount / 2
          }

        if (matchWins) {
          logger
            .debug(
              s"Tier 2 probe: SAME CHAIN — local=($localOrdinal,$localHash) matches $matchCount/$totalResponses " +
                s"probed peers (absent=$absentCount). Treating as lagging, no fork."
            )
            .as(none[ForkRecoveryInfo])
        } else if (mismatchWins) {
          val info = ForkRecoveryInfo(
            majorityOrdinal = majorityOrdinal,
            majorityHash = majorityHash,
            majorityPeers = majorityPeers,
            localOrdinal = localOrdinal,
            lag = lag
          )
          logger
            .warn(
              s"Fork divergence detected: isolated_minority_probed local=($localOrdinal,$localHash) " +
                s"$mismatchCount/$totalResponses probed peers report a DIFFERENT hash at our ordinal " +
                s"(match=$matchCount absent=$absentCount). majorityPeers=${majorityPeers.size}/$chainTipsSize"
            )
            .as(info.some)
        } else {
          logger
            .debug(
              s"Tier 2 probe: INCONCLUSIVE — match=$matchCount mismatch=$mismatchCount absent=$absentCount. " +
                s"Deferring fork decision to next detector cycle."
            )
            .as(none[ForkRecoveryInfo])
        }
      }
    }
  }
}
