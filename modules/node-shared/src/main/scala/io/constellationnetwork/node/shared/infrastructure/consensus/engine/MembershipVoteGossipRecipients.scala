package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.schema.peer.PeerId

/** Pure recipient policy shared by admission and eviction vote transport.
  *
  * Certified atomic Global L0 sends to the frozen round-start authority so local withdrawal timing cannot fragment certificate delivery.
  * Legacy Global L0 preserves its mutable current-facilitator transport. This selects transport recipients only; it does not alter vote
  * content, certificate authority, or consensus state.
  */
object MembershipVoteGossipRecipients {

  def select(
    selfId: PeerId,
    certifiedAtomicMembershipActive: Boolean,
    currentFacilitators: Set[PeerId],
    roundStartFacilitators: Set[PeerId]
  ): Set[PeerId] =
    (if (certifiedAtomicMembershipActive) roundStartFacilitators else currentFacilitators) - selfId
}
