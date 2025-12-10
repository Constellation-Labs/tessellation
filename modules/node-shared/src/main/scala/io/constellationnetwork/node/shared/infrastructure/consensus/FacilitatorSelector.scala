package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Order
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

/** Deterministic facilitator subset selection using hash-distance ordering.
  *
  * ==Algorithm==
  *
  * Given a set of candidate facilitators and entropy from the last consensus outcome, selects a deterministic subset by:
  *
  *   1. Computing XOR distance between each peer's ID hash and the entropy hash 2. Ordering peers by this distance (ascending) 3. Taking
  *      the first M peers (where M = maxFacilitatorCount)
  *
  * ==Properties==
  *
  *   - '''Deterministic''': All honest nodes compute the same subset given same inputs
  *   - '''Uniform distribution''': XOR with a hash provides IID randomness
  *   - '''Rotation''': Different entropy each round means different facilitators selected
  *   - '''Preserves existing behavior''': When maxFacilitatorCount >= candidate count, all are selected
  *
  * ==Usage==
  *
  * {{{
  *   val selector = FacilitatorSelector.make(maxFacilitatorCount = 20)
  *   val entropy = lastOutcome.finished.facilitatorHash
  *   val selected = selector.select(allCandidates, entropy)
  * }}}
  *
  * ==Security Considerations==
  *
  * The entropy comes from the previous consensus outcome's artifact hash, which is:
  *   - Not known until the previous round completes
  *   - Determined by consensus (not manipulable by a single node)
  *   - Different each round, ensuring facilitator rotation
  *
  * While more sophisticated approaches exist (VRFs, windowed entropy), this simple hash-distance method is sufficient given:
  *   - The reward distribution can be monitored for anomalies
  *   - The current security model already trusts the seedlist
  *   - Manipulating the hash to influence selection is costly and detectable
  */
object FacilitatorSelector {

  /** Creates a facilitator selector with the given maximum count.
    *
    * @param maxFacilitatorCount
    *   Maximum number of facilitators to select per round. If None, no subsetting is performed (all candidates selected).
    */
  def make(maxFacilitatorCount: Option[Int]): FacilitatorSelector =
    new FacilitatorSelector(maxFacilitatorCount)

  /** Computes XOR distance between two hex strings.
    *
    * XOR distance is computed byte-by-byte on the shorter of the two strings, then converted to a BigInt for ordering. This provides a
    * uniform distribution when one input is a cryptographic hash.
    */
  private[consensus] def xorDistance(a: String, b: String): BigInt = {
    val aBytes = hexToBytes(a)
    val bBytes = hexToBytes(b)

    val xorBytes = aBytes.zip(bBytes).map { case (x, y) => (x ^ y).toByte }

    BigInt(1, xorBytes) // signum=1 ensures positive
  }

  private def hexToBytes(hex: String): Array[Byte] =
    hex
      .grouped(2)
      .map(Integer.parseInt(_, 16).toByte)
      .toArray

  /** Order peers by their XOR distance to a reference hash. */
  private[consensus] def orderByDistance(referenceHash: Hash): Order[PeerId] =
    Order.by(peerId => xorDistance(peerId.value.value, referenceHash.value))
}

class FacilitatorSelector private (maxFacilitatorCount: Option[Int]) {

  /** Selects a deterministic subset of facilitators based on hash distance.
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
        implicit val distanceOrder: Order[PeerId] = FacilitatorSelector.orderByDistance(entropy)
        candidates.sorted(distanceOrder.toOrdering).take(maxCount).sorted
    }

  /** Returns the configured maximum facilitator count, if any. */
  def getMaxCount: Option[Int] = maxFacilitatorCount
}
