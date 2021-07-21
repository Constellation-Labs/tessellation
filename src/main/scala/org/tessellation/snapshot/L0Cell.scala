package org.tessellation.snapshot

import cats.effect.IO
import org.tessellation.schema.{Cell, CellError, StackF, Ω}

case class L0Cell(edge: L0Edge) extends Cell[IO, StackF, L0Edge, Either[CellError, Ω], Ω](edge, L0Snapshot.hyloM) {
  override def run(): IO[Either[CellError, Ω]] = hylo(CreateSnapshot(edge))
}
