package org.tessellation.dag.l1.domain.consensus.block

import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.kernel.Ω
import org.tessellation.schema.Block
import org.tessellation.security.Hashed

sealed trait BlockConsensusOutput extends Ω

object BlockConsensusOutput {
  case class FinalBlock(hashedBlock: Hashed[Block]) extends BlockConsensusOutput
  case class CleanedConsensuses(ids: Set[RoundId]) extends BlockConsensusOutput
  case object NoData extends BlockConsensusOutput
}
