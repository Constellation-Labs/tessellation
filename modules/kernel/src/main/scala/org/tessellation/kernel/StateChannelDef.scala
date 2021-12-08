package org.tessellation.kernel

import cats.effect.Async

import org.tessellation.schema.address.Address

import fs2.Pipe

trait StateChannelDef[A <: Ω, B <: Ω, S <: Ω] {

  val address: Address
  val kryoRegistrar: Map[Class[_], KryoRegistrationId]

  def makeCell[F[_]: Async](
    input: A,
    hgContext: HypergraphContext[F]
  ): Cell[F, StackF, A, Either[CellError, B], S]

  def inputPipe[F[_]: Async, In <: A]: Pipe[F, In, A] = identity
  def outputPipe[F[_]: Async, Out >: B]: Pipe[F, B, Out] = identity
}
