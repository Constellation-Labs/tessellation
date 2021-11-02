package org.tesselation.domain

import org.tesselation.kernel._

package object aci {
  type StdCell[F[_]] = Cell[F, StackF, Ω, Either[CellError, Ω], Ω]
}
