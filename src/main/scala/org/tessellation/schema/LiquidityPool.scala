package org.tessellation.schema

import cats.effect.IO
import cats.effect.concurrent.Ref

/**
  * Based on Constant Product Market Maker Algorithm (Automated Market Maker)
  * https://ethereum.stackexchange.com/questions/103163/xy-k-constant-product-market-maker
  */
case class LiquidityPool[X <: Currency, Y <: Currency](
  xInitialQuantity: Long,
  yInitialQuantity: Long,
  xAddress: String,
  yAddress: String
) {
  private val kConstant: Long = xInitialQuantity * yInitialQuantity
  private val poolQuantity: Ref[IO, (Long, Long)] = Ref.unsafe[IO, (Long, Long)]((xInitialQuantity, yInitialQuantity))

  def updatePoolByOffer(xOffer: Long): IO[Long] = poolQuantity.modify {
    case (xQuantity, yQuantity) =>
      val yOffer = howManyYForX(xOffer, xQuantity, yQuantity)
      val adjustedPoolQuantity = (xQuantity + xOffer, yQuantity)
      (adjustedPoolQuantity, yOffer)
  }

  def howManyYForX(xToSwap: Long, xQuantity: Long, yQuantity: Long): Long =
    yQuantity - kConstant / (xQuantity + xToSwap)
}
