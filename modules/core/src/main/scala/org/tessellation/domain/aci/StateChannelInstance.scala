package org.tessellation.domain.aci

import org.tessellation.kernel._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address

import fs2.Pipe

trait StateChannelInstance[F[_]] {

  val address: Address

  val kryoSerializer: KryoSerializer[F]

  def makeCell(input: Ω, hgContext: HypergraphContext[F]): Cell[F, StackF, Ω, Either[CellError, Ω], Ω]

  def inputPipe: Pipe[F, Ω, Ω]
  def outputPipe: Pipe[F, Ω, Ω]

}
