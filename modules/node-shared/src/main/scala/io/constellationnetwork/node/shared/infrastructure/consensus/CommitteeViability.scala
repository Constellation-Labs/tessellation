package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumPolicy

/** Protocol-derived committee viability rules shared by normal consensus and operator recovery.
  *
  * Two is the smallest committee where mutual attestation and leader rotation are meaningful. Whether a committee can grow is a separate
  * question derived from its configured named quorum mode: all `N` current peers must be able to meet `Q(N+1)`. Under supermajority a fully
  * participating pair can grow; under unanimity no finite committee can prove an unseated next signer. These are not reward-population caps
  * or Core-size policies.
  */
object CommitteeViability {
  val MinimumCoordinatedCommitteeSize: Int = 2

  def supportsCoordination(size: Int): Boolean = size >= MinimumCoordinatedCommitteeSize

  def canProveNextSeat(size: Int, quorumThresholdFraction: Double): Boolean =
    size > 0 && size >= QuorumPolicy.fromFraction(size + 1, quorumThresholdFraction)
}
