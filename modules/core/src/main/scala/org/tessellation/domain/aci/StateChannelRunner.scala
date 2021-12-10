package org.tessellation.domain.aci

import cats.data.OptionT

import org.tessellation.schema.address.Address

trait StateChannelRunner[F[_]] {
  def initializeKnownCells: F[Unit]
  def initializeCell(address: Address): F[Unit]
  def routeInput(input: StateChannelInput): OptionT[F, Unit]
}
