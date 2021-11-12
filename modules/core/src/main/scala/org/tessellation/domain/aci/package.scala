package org.tessellation.domain

import org.tessellation.kernel._

package object aci {
  type StdCell[F[_]] = Cell[F, StackF, Ω, Either[CellError, Ω], Ω]
}
