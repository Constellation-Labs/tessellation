package org.tessellation.consensus

import cats.effect.IO
import org.tessellation.consensus.L1ConsensusStep.{L1ConsensusContext, L1ConsensusMetadata}
import org.tessellation.schema.{Cell, CellError, StackF, Ω}

case class L1Cell(edge: L1Edge) extends Cell[IO, StackF, L1Edge, Either[CellError, Ω], (L1ConsensusMetadata, Ω)](edge, L1Consensus.hyloM) {

  def run(context: L1ConsensusContext, cmd: L1Edge => Ω): IO[Either[CellError, Ω]] =
    hyloM(edge => (L1ConsensusMetadata.empty(context), cmd(edge)))
}
