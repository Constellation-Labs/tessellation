package org.tessellation.kernel

import org.tessellation.schema.address.Address

trait HypergraphContext[F[_]] {
  def getStateChannelContext(address: Address): F[Option[StateChannelContext[F]]]
}
