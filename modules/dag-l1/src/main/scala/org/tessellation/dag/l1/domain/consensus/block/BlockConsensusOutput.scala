package org.tessellation.dag.l1.domain.consensus.block

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.kernel.Ω
import org.tessellation.schema.security.Hashed

sealed trait BlockConsensusOutput extends Ω

object BlockConsensusOutput {
  case class FinalBlock(hashedBlock: Hashed[DAGBlock]) extends BlockConsensusOutput
  case class CleanedConsensuses(ids: Set[RoundId]) extends BlockConsensusOutput
}
