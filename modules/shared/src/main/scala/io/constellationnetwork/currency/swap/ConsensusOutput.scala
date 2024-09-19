package io.constellationnetwork.currency.swap

import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.swap.SwapBlock
import io.constellationnetwork.security.Hashed

sealed trait ConsensusOutput

object ConsensusOutput {

  case class FinalBlock(hashedBlock: Hashed[SwapBlock]) extends ConsensusOutput
  case class CleanedConsensuses(ids: Set[RoundId]) extends ConsensusOutput
  case object Noop extends ConsensusOutput
}
