package io.constellationnetwork.node.shared.domain.tokenlock.consensus

import io.constellationnetwork.schema.round.RoundId

case class ConsensusState(
  ownConsensus: Option[RoundData],
  peerConsensuses: Map[RoundId, RoundData]
)

object ConsensusState {
  def Empty = ConsensusState(None, Map.empty)
}
