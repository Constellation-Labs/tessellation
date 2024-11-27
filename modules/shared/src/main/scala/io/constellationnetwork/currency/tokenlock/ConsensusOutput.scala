package io.constellationnetwork.currency.tokenlock

import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.Hashed

sealed trait ConsensusOutput

object ConsensusOutput {

  case class FinalBlock(hashedBlock: Hashed[TokenLockBlock]) extends ConsensusOutput
  case class CleanedConsensuses(ids: Set[RoundId]) extends ConsensusOutput
  case object Noop extends ConsensusOutput
}
