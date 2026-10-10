package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.peer.PeerId

/** Selects the single open-admission peer a leader carries in its Proposal.
  *
  * Candidate advertisements are local at Facility quorum-crossing time, so this selection is construction policy, not independently
  * re-derived consensus state. The leader's signed Proposal makes the selected value canonical for next-round voters. Candidates rotate by
  * admission-opportunity index (see [[DeterministicRotation.nominationIndex]]), so every eligible candidate is nominated within
  * \|candidates| opportunities and no snapshot hash can bias the choice.
  */
object AdmissionNomineeSelector {

  def select(
    candidates: Iterable[PeerId],
    excluded: Set[PeerId],
    opportunityIndex: Long
  ): Option[PeerId] =
    DeterministicRotation.rotated(candidates.iterator.filterNot(excluded.contains).toList, opportunityIndex).headOption
}
