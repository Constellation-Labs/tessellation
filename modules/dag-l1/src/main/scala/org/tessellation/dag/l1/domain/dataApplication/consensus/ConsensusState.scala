package org.tessellation.dag.l1.domain.dataApplication.consensus

import org.tessellation.dag.l1.domain.consensus.round.RoundId

case class ConsensusState(
  ownConsensus: Option[RoundData],
  peerConsensuses: Map[RoundId, RoundData]
)

object ConsensusState {
  def Empty = ConsensusState(None, Map.empty)
}
