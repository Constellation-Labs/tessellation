package io.constellationnetwork.node.shared.infrastructure.consensus

import java.security.MessageDigest

import cats.Order
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

/** Deterministic facilitator subset selection using rendezvous hashing.
  *
  * ==Algorithm==
  *
  * Given a set of candidate facilitators and entropy from the last consensus outcome, selects a deterministic subset by:
  *
  *   1. Computing a per-peer score as `SHA-256(entropy ++ peerId)` 2. Ordering peers by this score (ascending) 3. Taking the first M peers
  *      (where M = maxFacilitatorCount)
  *
  * This is a '''rendezvous hashing''' (Highest Random Weight) scheme. Each peer's score is independently derived from the entropy,
  * producing IID uniform selection probabilities.
  *
  * ==Properties==
  *
  *   - '''Deterministic''': All honest nodes compute the same subset given same inputs
  *   - '''IID uniform distribution''': Each peer's score is independently random
  *   - '''Zero autocorrelation''': Selection in round N is independent of round N-1
  *   - '''Preserves existing behavior''': When maxFacilitatorCount >= candidate count, all are selected
  *
  * ==Why not XOR distance?==
  *
  * The previous XOR-distance approach (`XOR(peerId, entropy)`) suffers from most-significant-bit dominance: the top bits of the XOR result
  * determine selection, creating implicit "buckets" of peers that are always selected or excluded together. This produces bimodal overlap
  * distributions (50% zero overlap, 18% near-total overlap between consecutive rounds) and up to 2x variance in per-peer selection rates.
  * Rendezvous hashing eliminates this by fully mixing entropy and peer identity through the hash function.
  *
  * ==Security Considerations==
  *
  * The entropy comes from the previous consensus outcome's artifact hash, which is:
  *   - Not known until the previous round completes
  *   - Determined by consensus (not manipulable by a single node)
  *   - Different each round, ensuring facilitator rotation
  */
object FacilitatorSelector {

  /** Creates a facilitator selector with the given maximum count.
    *
    * @param maxFacilitatorCount
    *   Maximum number of facilitators to select per round. If None, no subsetting is performed (all candidates selected).
    */
  def make(maxFacilitatorCount: Option[Int]): FacilitatorSelector =
    new FacilitatorSelector(maxFacilitatorCount)

  /** Computes a deterministic score for a peer given entropy using rendezvous hashing.
    *
    * The score is `SHA-256(entropyBytes ++ peerIdBytes)` interpreted as a positive BigInt. Because SHA-256 is a cryptographic hash, the
    * scores for different peers are independently and uniformly distributed for any fixed entropy, and vice versa.
    */
  private[consensus] def rendezvousScore(peerIdHex: String, entropyHex: String): BigInt = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(hexToBytes(entropyHex))
    md.update(hexToBytes(peerIdHex))
    BigInt(1, md.digest()) // signum=1 ensures positive
  }

  private def hexToBytes(hex: String): Array[Byte] =
    hex
      .grouped(2)
      .map(Integer.parseInt(_, 16).toByte)
      .toArray

  /** Order peers by their rendezvous score relative to a reference hash. */
  private[consensus] def orderByScore(referenceHash: Hash): Order[PeerId] =
    Order.by(peerId => rendezvousScore(peerId.value.value, referenceHash.value))
}

class FacilitatorSelector private (maxFacilitatorCount: Option[Int]) {

  /** Selects a deterministic subset of facilitators using rendezvous hashing.
    *
    * @param candidates
    *   All peers eligible to be facilitators (already filtered by seedlist/collateral)
    * @param entropy
    *   Hash from the last consensus outcome to use as selection entropy
    * @return
    *   Selected facilitators, sorted for consistency
    */
  def select(
    candidates: List[PeerId],
    entropy: Hash
  ): List[PeerId] =
    maxFacilitatorCount match {
      case None =>
        candidates.sorted

      case Some(maxCount) if candidates.size <= maxCount =>
        candidates.sorted

      case Some(maxCount) =>
        implicit val scoreOrder: Order[PeerId] = FacilitatorSelector.orderByScore(entropy)
        candidates.sorted(scoreOrder.toOrdering).take(maxCount).sorted
    }

  /** Selects a deterministic leader from facilitators using rendezvous hashing.
    *
    * The leader is the highest-scoring facilitator. All nodes compute the same leader given the same inputs. The `viewNumber` parameter
    * allows rotating the leader on view change: for view 0 it picks rank 0, for view 1 rank 1, etc.
    *
    * @param facilitators
    *   The already-selected facilitators for this round
    * @param entropy
    *   Hash from the last consensus outcome
    * @param viewNumber
    *   Current view number (0 = initial leader, incremented on view change)
    * @return
    *   The selected leader PeerId
    */
  def selectLeader(
    facilitators: List[PeerId],
    entropy: Hash,
    viewNumber: Int = 0
  ): PeerId = {
    require(facilitators.nonEmpty, "selectLeader called with empty facilitators list — consensus cannot proceed without facilitators")
    implicit val scoreOrder: Order[PeerId] = FacilitatorSelector.orderByScore(entropy)
    val sorted = facilitators.sorted(scoreOrder.toOrdering)
    val index = viewNumber % sorted.size
    sorted(index)
  }

  /** Selects a leader with quality-weighted scoring using integer-only tier computation.
    *
    * Uses consensus-agreed quality scores (completed, participated) AND each peer's last-known `SelfHealthHint` to compute deterministic
    * tiers. Integer arithmetic avoids platform-dependent float-to-long conversion differences that could cause different nodes to compute
    * different tiers, different leaders, or a fork.
    *
    * '''Tiering rule:'''
    *   - Critical self-health: tier 2 (selected only when no tier 0/1 peer exists in the pool; avoids deadlock if every peer reports
    *     Critical).
    *   - Degraded self-health: tier 1 (fallback band).
    *   - Healthy self-health (or missing entry, default Healthy): tier 0 if completion ratio `completed / participated >= minLeaderRatioPct
    *     / 100` OR `participated == 0` (bootstrap fallback); else tier 1.
    *
    * Chronic peers are kept out of the leader slot via two layers: the graduation filter at the call-site (`participated >= minObservations
    * && completed >= 1`) excludes peers that have never delivered, and the tier-1 band here deprioritizes peers below the ratio threshold.
    *
    * Schema/deploy history (binary band introduced v14; self-health throttle added v15) lives in `ConsensusConfig.consensusSchemaVersion`
    * documentation.
    *
    * Callers should pre-filter `facilitators` to a leader-eligible pool (e.g. by `participated >= minObservations`) so that unproven peers
    * are not given the leader slot.
    *
    * @param facilitators
    *   The already-selected facilitators for this round (or a leader-eligible subset of them)
    * @param entropy
    *   Hash from the last consensus outcome
    * @param viewNumber
    *   Current view number (0 = initial leader, incremented on view change)
    * @param qualityScores
    *   Map of peer → (completedRounds, participatedRounds) from consensus-agreed outcome. Must be identical across all nodes for
    *   determinism.
    * @param minLeaderRatioPct
    *   Integer percent threshold (0..100): peers with `completed/participated >= minLeaderRatioPct/100` are leader-eligible (tier 0).
    *   Default 50 mirrors the existing chronic-classification ratio. Integer arithmetic only -- no floats anywhere in this function.
    * @return
    *   The selected leader PeerId
    */
  def selectLeaderWeighted(
    facilitators: List[PeerId],
    entropy: Hash,
    viewNumber: Int = 0,
    qualityScores: Map[PeerId, (Int, Int)] = Map.empty,
    selfHealthHints: Map[PeerId, SelfHealthHint] = Map.empty,
    minLeaderRatioPct: Int = 50
  ): PeerId = {
    require(
      facilitators.nonEmpty,
      "selectLeaderWeighted called with empty facilitators list — consensus cannot proceed without facilitators"
    )
    // v15 tiering. Self-health hint applied first; observed completion ratio applied within
    // Healthy. Integer-only arithmetic for determinism across JVM platforms.
    //   tier 0 = Healthy + leader-eligible (ratio >= threshold, OR no history at all)
    //   tier 1 = Healthy below ratio threshold OR Degraded self-report
    //   tier 2 = Critical self-report (strong demote, selected only as ultimate fallback)
    // Within a tier, rendezvous score (entropy-dependent) decides ordering so view 0 picks a
    // different peer each round and leadership spreads across the eligible pool.
    val sorted = facilitators.sortBy { pid =>
      val rendezvousScore = FacilitatorSelector.rendezvousScore(pid.value.value, entropy.value)
      val (completed, participated) = qualityScores.getOrElse(pid, (0, 0))
      val hint = selfHealthHints.getOrElse(pid, SelfHealthHint.Healthy)
      val tier: Long = hint match {
        case SelfHealthHint.Critical => 2L
        case SelfHealthHint.Degraded => 1L
        case SelfHealthHint.Healthy =>
          if (participated == 0) 0L
          else if (completed.toLong * 100L >= participated.toLong * minLeaderRatioPct.toLong) 0L
          else 1L
      }
      (tier, rendezvousScore)
    }
    val index = viewNumber % sorted.size
    sorted(index)
  }

  /** Returns the configured maximum facilitator count, if any. */
  def getMaxCount: Option[Int] = maxFacilitatorCount
}
