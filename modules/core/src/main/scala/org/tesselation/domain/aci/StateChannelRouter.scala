package org.tesselation.domain.aci

import cats.data.OptionT

import org.tesselation.kernel.Ω

trait StateChannelRouter[F[_]] {
  def routeInput(input: StateChannelInput): OptionT[F, Ω]
}
