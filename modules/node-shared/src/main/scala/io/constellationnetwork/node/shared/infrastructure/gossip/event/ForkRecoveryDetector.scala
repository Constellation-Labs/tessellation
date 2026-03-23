package io.constellationnetwork.node.shared.infrastructure.gossip.event

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

import org.typelevel.log4cats.slf4j.Slf4jLogger

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
  * 2. **Running fork**: Local ordinal matches or exceeds the majority, but with a DIFFERENT hash. The node is on a parallel chain producing
  * its own snapshots (e.g., a 2-node mini-fork after partition). Detected when: peers at the same ordinal have a different hash AND those
  * peers form a majority.
  *
  * The majority threshold uses strict majority (> 50% of reporting peers). This is intentional: with 4+ nodes required for quorum, > 50% of
  * chain tip reporters is a reliable signal that the node has diverged.
  */
trait ForkRecoveryDetector[F[_]] {
  def detectForkDivergence: F[Option[ForkRecoveryInfo]]
}

object ForkRecoveryDetector {

  def make[F[_]: Async](
    meshState: MeshState[F],
    getLocalChainTip: F[Option[ChainTip]],
    forkLagThreshold: Long = 2
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

              // Check 2: Running fork — same or higher ordinal but different hash.
              // Find peers at our ordinal: if a majority of them have a different hash, we're forked.
              val peersAtLocalOrdinal = chainTips.filter { case (_, tip) => tip.ordinal == localOrdinal }
              val peersWithDifferentHash = peersAtLocalOrdinal.filter { case (_, tip) => tip.snapshotHash != localHash }
              // Intentionally conservative: requires strict majority (> 50%) of peers at our ordinal
              // to disagree on hash. With 2 peers, both must disagree (unanimity). This avoids
              // false positives from temporary network jitter at the cost of slower detection
              // for 2-node mini-forks. The lagging fork check (forkLagThreshold) catches those
              // cases once the mini-fork falls behind the canonical chain.
              val isRunningFork = peersAtLocalOrdinal.size >= 2 && peersWithDifferentHash.size > peersAtLocalOrdinal.size / 2

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
              } else none[ForkRecoveryInfo].pure[F]
            }
          case _ => none[ForkRecoveryInfo].pure[F]
        }
      } yield result
  }
}
