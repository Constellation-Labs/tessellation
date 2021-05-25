package org.tessellation.snapshot

import cats.effect.IO
import org.tessellation.consensus.L1ParticipateInConsensusCell
import org.tessellation.schema.{Cell, CellError, StackF, Ω}

case class L0Cell(edge: L0Edge)
    extends Cell[IO, StackF, L0Edge, Either[CellError, Ω], Ω](edge, L0Snapshot.hyloM, identity) {
  override def run(): IO[Either[CellError, Ω]] = hylo(CreateSnapshot(edge))
}

object L0Cell {
  implicit def toCell[M[_], F[_]](a: L0Cell): Cell[M, F, Ω, Either[CellError, Ω], Ω] =
    a.asInstanceOf[Cell[M, F, Ω, Either[CellError, Ω], Ω]]
}
