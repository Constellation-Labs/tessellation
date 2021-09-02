package org.tessellation

import org.tessellation.schema.{Cell, CellError, StackF, Ω}

package object aci {
  type StdCell[F[_]] = Cell[F, StackF, Ω, Either[CellError, Ω], Ω]
}
