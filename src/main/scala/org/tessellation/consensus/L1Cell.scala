package org.tessellation.consensus

import L1ConsensusStep.{L1ConsensusContext, L1ConsensusMetadata}
import org.tessellation.schema.{Cell, Ω}

case class L1Cell(edge: L1Edge[L1Transaction]) extends Cell(edge, L1Consensus.algebra, L1Consensus.coalgebra) {

  def run(context: L1ConsensusContext, cmd: L1Edge[L1Transaction] => Ω) =
    hyloM(edge => (L1ConsensusMetadata.empty(context), cmd(edge)))
}
