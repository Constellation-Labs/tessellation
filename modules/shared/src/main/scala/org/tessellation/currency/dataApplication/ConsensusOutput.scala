package org.tessellation.currency.dataApplication

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.schema.round.RoundId
import org.tessellation.security.Hashed

sealed trait ConsensusOutput

object ConsensusOutput {

  case class FinalBlock(hashedBlock: Hashed[DataApplicationBlock]) extends ConsensusOutput
  case class CleanedConsensuses(ids: Set[RoundId]) extends ConsensusOutput
  case object Noop extends ConsensusOutput
}
