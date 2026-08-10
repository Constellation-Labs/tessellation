package io.constellationnetwork.node.shared.infrastructure.consensus.state

import io.constellationnetwork.schema.peer.PeerId

/** Shared voter-membership rule for admission certificate assembly and validation.
  *
  * Open expansion is Core-certified so Tier 1 cannot become a liveness dependency. Penalty/probation readmission retains the wider
  * deterministic witness lane needed to recover from a degraded committee. The target never votes for itself.
  */
object AdmissionVoterPool {

  def select(
    target: PeerId,
    isProbationReadmission: Boolean,
    core: Set[PeerId],
    widerWitnessPool: Set[PeerId]
  ): Set[PeerId] =
    (if (isProbationReadmission) widerWitnessPool else core) - target
}
