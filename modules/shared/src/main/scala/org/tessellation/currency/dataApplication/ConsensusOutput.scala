package org.tessellation.currency.dataApplication

import org.tessellation.schema.round.RoundId
import org.tessellation.security.Hashed

import dataApplication.DataApplicationBlock

sealed trait ConsensusOutput

object ConsensusOutput {

  case class FinalBlock(hashedBlock: Hashed[DataApplicationBlock]) extends ConsensusOutput
  case class CleanedConsensuses(ids: Set[RoundId]) extends ConsensusOutput
  case object Noop extends ConsensusOutput
}
