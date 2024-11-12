package io.constellationnetwork.currency.swap

import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.security.Hashed

sealed trait ConsensusOutput

object ConsensusOutput {

  case class FinalBlock(hashedBlock: Hashed[AllowSpendBlock]) extends ConsensusOutput
  case class CleanedConsensuses(ids: Set[RoundId]) extends ConsensusOutput
  case object Noop extends ConsensusOutput
}
