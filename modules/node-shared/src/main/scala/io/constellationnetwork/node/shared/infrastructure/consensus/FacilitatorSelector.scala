package io.constellationnetwork.node.shared.infrastructure.consensus

import java.security.MessageDigest

import cats.Order
import cats.syntax.all._

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

  /** Returns the configured maximum facilitator count, if any. */
  def getMaxCount: Option[Int] = maxFacilitatorCount
}
