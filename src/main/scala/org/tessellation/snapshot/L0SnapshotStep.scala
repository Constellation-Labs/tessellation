package org.tessellation.snapshot

import cats.effect.IO
import higherkindness.droste.{AlgebraM, CoalgebraM}
import org.tessellation.schema.{CellError, Ω}

object L0SnapshotStep {

  val coalgebra: CoalgebraM[IO, L0SnapshotF, Ω] = ???

  val algebra: AlgebraM[IO, L0SnapshotF, Either[CellError, Ω]] = ???

}
