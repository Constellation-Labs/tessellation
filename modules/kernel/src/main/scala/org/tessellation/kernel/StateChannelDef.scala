package org.tessellation.kernel

import cats.Monad

import org.tessellation.schema.address.Address

trait StateChannelDef[A <: Ω, B <: Ω, S <: Ω] {

  val address: Address
  val kryoRegistrar: Map[Class[_], KryoRegistrationId]

  def makeCell[F[_]: Monad](
    input: A,
    hgContext: HypergraphContext[F]
  ): Cell[F, StackF, A, Either[CellError, B], S]
}
