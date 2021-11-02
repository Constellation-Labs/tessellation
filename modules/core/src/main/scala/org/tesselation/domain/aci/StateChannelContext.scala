package org.tesselation.domain.aci

import org.tesselation.schema.address.Address

trait StateChannelContext[F[_]] {
  val address: Address
  def createCell(input: Array[Byte]): F[StdCell[F]]
}
