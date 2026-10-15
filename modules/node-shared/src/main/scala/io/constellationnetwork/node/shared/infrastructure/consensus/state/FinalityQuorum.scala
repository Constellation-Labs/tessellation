package io.constellationnetwork.node.shared.infrastructure.consensus.state

/** Quorum derivation over one frozen round committee (ADR-0021 finality floor).
  *
  * Liveness certificates (VCC/TC assembly and apply, proposal-embedded certificate validation, stall feasibility) use the Core-sized quorum
  * so a degraded committee can still rotate a dead leader. Their effects only land through a finalized snapshot, and finalization is
  * floored: outside bootstrap (`applyClusterFloor`) the finality quorum is at least a supermajority of the frozen `roundStartFacilitators`,
  * so a Core that has shrunk to a cluster minority can never finalize. Under more than `f` failures the round halts rather than letting a
  * minority finalize.
  *
  * Certified consensus (ADR-0032) never lowers the quorum denominator at a stuck key; the former escalating shrink rung is retired.
  */
object FinalityQuorum {

  def coreQuorum(coreSize: Int, quorumThresholdFraction: Double): Int =
    math.max(1, QuorumPolicy.fromFraction(coreSize, quorumThresholdFraction))

  /** `max(coreQuorum, clusterFloor)` where `clusterFloor = fromFraction(roundStartFacilitators, fraction)` when the floor applies and 0
    * otherwise. Because Core is a subset of the frozen committee, outside bootstrap this equals the committee floor.
    */
  def required(
    coreSize: Int,
    roundStartFacilitatorsSize: Int,
    applyClusterFloor: Boolean,
    quorumThresholdFraction: Double
  ): Int = {
    val clusterFloor =
      if (applyClusterFloor) math.max(1, QuorumPolicy.fromFraction(roundStartFacilitatorsSize, quorumThresholdFraction)) else 0
    math.max(coreQuorum(coreSize, quorumThresholdFraction), clusterFloor)
  }
}
