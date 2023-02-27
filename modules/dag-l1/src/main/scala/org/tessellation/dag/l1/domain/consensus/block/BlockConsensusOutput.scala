package org.tessellation.dag.l1.domain.consensus.block

import org.tessellation.dag.l1.domain.consensus.round.RoundId
import org.tessellation.kernel.Ω
import org.tessellation.schema.Block
import org.tessellation.security.Hashed

sealed trait BlockConsensusOutput[+B <: Block[_]] extends Ω

object BlockConsensusOutput {
  case class FinalBlock[B <: Block[_]](hashedBlock: Hashed[B]) extends BlockConsensusOutput[B]
  case class CleanedConsensuses(ids: Set[RoundId]) extends BlockConsensusOutput[Nothing]
  case object NoData extends BlockConsensusOutput[Nothing]
}
