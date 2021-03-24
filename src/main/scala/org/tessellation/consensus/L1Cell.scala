package org.tessellation.consensus

import L1ConsensusStep.{L1ConsensusContext, L1ConsensusMetadata}
import cats.effect.IO
import org.tessellation.schema.{Cell, CellError, Ω}

case class L1Cell(edge: L1Edge) extends Cell(edge, L1Consensus.algebra, L1Consensus.coalgebra) {

  def run(context: L1ConsensusContext, cmd: L1Edge => Ω): IO[Either[CellError, Ω]] =
    hyloM(edge => (L1ConsensusMetadata.empty(context), cmd(edge)))
}
