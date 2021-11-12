package org.tessellation.domain.aci

import cats.data.OptionT

import org.tessellation.kernel.Ω

trait StateChannelRouter[F[_]] {
  def routeInput(input: StateChannelInput): OptionT[F, Ω]
}
