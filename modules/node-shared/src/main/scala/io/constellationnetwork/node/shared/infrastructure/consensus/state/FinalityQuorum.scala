package io.constellationnetwork.node.shared.infrastructure.consensus.state

/** Quorum over the one frozen round committee (ADR-0021 finality floor).
  *
  * Phase gates, view-change and timeout certificates, and finalization all require the same supermajority of `roundStartFacilitators`.
  * Under more than `f` failures the round halts; certified consensus (ADR-0032) never lowers the denominator at a stuck key.
  */
object FinalityQuorum {

  def of(committeeSize: Int, quorumThresholdFraction: Double): Int =
    math.max(1, QuorumPolicy.fromFraction(committeeSize, quorumThresholdFraction))
}
