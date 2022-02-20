package org.tessellation.kernel

import cats.effect.Async

import org.tessellation.schema.address.Address

import fs2.Pipe

trait StateChannelDef[A <: Ω, B <: Ω, S <: Ω] {

  val address: Address
  val kryoRegistrar: Map[Class[_], StateChannelKryoRegistrationId]

  def makeCell[F[_]: Async](
    input: A,
    hgContext: HypergraphContext[F]
  ): Cell[F, StackF, A, Either[CellError, B], S]

  def inputPipe[F[_]: Async]: Pipe[F, A, A] = identity
  def outputPipe[F[_]: Async]: Pipe[F, B, B] = identity
}
