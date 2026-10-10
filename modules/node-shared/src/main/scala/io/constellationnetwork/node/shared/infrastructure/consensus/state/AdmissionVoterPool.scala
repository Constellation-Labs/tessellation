package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus
import io.constellationnetwork.schema.peer.PeerId

/** Admission certificates are voted by the single frozen voting committee. The target never votes for itself. */
object AdmissionVoterPool {

  def allowsVoteEmission(voter: PeerId, committee: Set[PeerId]): Boolean = committee.contains(voter)

  def requiredQuorum(committeeSize: Int, quorumThresholdFraction: Double): Int =
    CertifiedConsensus.requiredCoreQuorum(committeeSize, quorumThresholdFraction)

  def select(target: PeerId, committee: Set[PeerId]): Set[PeerId] = committee - target
}
