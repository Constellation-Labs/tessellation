package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Order

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

/** Selects the single open-admission peer a leader carries in its Proposal.
  *
  * Candidate advertisements are local at Facility quorum-crossing time, so this selection is construction policy, not independently
  * re-derived consensus state. The leader's signed Proposal makes the selected value canonical for next-round voters. Ordering uses the
  * existing rendezvous score and a PeerId tie-break; input order and duplicates cannot influence it.
  */
object AdmissionNomineeSelector {

  def select(
    candidates: Iterable[PeerId],
    excluded: Set[PeerId],
    entropy: Hash
  ): Option[PeerId] = {
    implicit val scoreOrder: Order[PeerId] = FacilitatorSelector.orderByScore(entropy)

    candidates.iterator
      .filterNot(excluded.contains)
      .toList
      .distinct
      .sorted(scoreOrder.toOrdering)
      .headOption
  }
}
