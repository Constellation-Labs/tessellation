package io.constellationnetwork.dag.l1.domain.consensus.block

import io.constellationnetwork.kernel.Ω
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.security.Hashed

sealed trait BlockConsensusOutput extends Ω

object BlockConsensusOutput {
  case class FinalBlock(hashedBlock: Hashed[Block]) extends BlockConsensusOutput
  case class CleanedConsensuses(ids: Set[RoundId]) extends BlockConsensusOutput
  case object NoData extends BlockConsensusOutput
}
