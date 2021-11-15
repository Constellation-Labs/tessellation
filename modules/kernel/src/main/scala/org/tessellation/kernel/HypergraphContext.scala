package org.tessellation.kernel

import org.tessellation.schema.address.Address

trait HypergraphContext[F[_]] {
  def getStateChannelContext(address: Address): F[Option[StateChannelContext[F]]]

  def createCell(stateChannel: Address)(input: Array[Byte]): F[Option[Cell[F, StackF, Ω, Either[CellError, Ω], Ω]]] // None when state channel does not exists
}
