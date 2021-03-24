package org.tessellation.snapshot

import cats.effect.IO
import org.tessellation.schema.{Cell, CellError, Ω}

case class L0Cell(edge: L0Edge) extends Cell(edge, L0Snapshot.algebra, L0Snapshot.coalgebra) {

  def run(): IO[Either[CellError, Ω]] = hyloM(edge => CreateSnapshot(edge))

}
