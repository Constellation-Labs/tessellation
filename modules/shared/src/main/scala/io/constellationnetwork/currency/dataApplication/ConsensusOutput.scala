package io.constellationnetwork.currency.dataApplication

import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.security.Hashed

import dataApplication.DataApplicationBlock

sealed trait ConsensusOutput

object ConsensusOutput {

  case class FinalBlock(hashedBlock: Hashed[DataApplicationBlock]) extends ConsensusOutput
  case class CleanedConsensuses(ids: Set[RoundId]) extends ConsensusOutput
  case object Noop extends ConsensusOutput
}
