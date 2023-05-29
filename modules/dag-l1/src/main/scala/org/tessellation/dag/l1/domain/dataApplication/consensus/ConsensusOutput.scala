package org.tessellation.dag.l1.domain.dataApplication.consensus

import org.tessellation.currency.dataApplication.DataApplicationBlock
import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.security.Hashed

sealed trait ConsensusOutput

object ConsensusOutput {

  case class FinalBlock(hashedBlock: Hashed[DataApplicationBlock]) extends ConsensusOutput
  case class CleanedConsensuses(ids: Set[RoundId]) extends ConsensusOutput
  case object Noop extends ConsensusOutput
}
