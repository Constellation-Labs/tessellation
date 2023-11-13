package org.tessellation.currency.l1.domain.dataApplication.consensus

import org.tessellation.schema.round.RoundId

case class ConsensusState(
  ownConsensus: Option[RoundData],
  peerConsensuses: Map[RoundId, RoundData]
)

object ConsensusState {
  def Empty = ConsensusState(None, Map.empty)
}
