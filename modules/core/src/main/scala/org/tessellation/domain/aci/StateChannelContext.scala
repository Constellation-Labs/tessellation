package org.tessellation.domain.aci

import org.tessellation.schema.address.Address

trait StateChannelContext[F[_]] {
  val address: Address
  def createCell(input: Array[Byte]): F[StdCell[F]]
}
