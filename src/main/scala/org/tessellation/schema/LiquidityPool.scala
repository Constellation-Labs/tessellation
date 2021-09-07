package org.tessellation.schema

import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.{Ref, Semaphore}

/**
  * Based on Constant Product Market Maker Algorithm (Automated Market Maker)
  * https://ethereum.stackexchange.com/questions/103163/xy-k-constant-product-market-maker
  */
case class LiquidityPool[X <: Currency, Y <: Currency](
  xInitialQuantity: BigDecimal,
  yInitialQuantity: BigDecimal,
  xAddress: String,
  yAddress: String
)(semaphore: Semaphore[IO]) {
  private val kConstant: BigDecimal = xInitialQuantity * yInitialQuantity
  private val poolQuantity: Ref[IO, (BigDecimal, BigDecimal)] =
    Ref.unsafe[IO, (BigDecimal, BigDecimal)]((xInitialQuantity, yInitialQuantity))

  def swap[A](xOffer: BigDecimal)(yOfferFn: BigDecimal => IO[A]): IO[A] = semaphore.withPermit {
    for {
      (xQuantity, yQuantity) <- poolQuantity.get
      yOffer = howManyYForX(xOffer, xQuantity, yQuantity)
      _ <- poolQuantity.set((xQuantity + xOffer, yQuantity))
      a <- yOfferFn(yOffer)
    } yield a
  }

  def howManyYForX(xToSwap: BigDecimal, xQuantity: BigDecimal, yQuantity: BigDecimal): BigDecimal =
    yQuantity - kConstant / (xQuantity + xToSwap)
}

object LiquidityPool {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  def init[X <: Currency, Y <: Currency](
    xInitialQuantity: BigDecimal,
    yInitialQuantity: BigDecimal,
    xAddress: String,
    yAddress: String
  ): IO[LiquidityPool[X, Y]] =
    for {
      s <- Semaphore[IO](1)
      instance = LiquidityPool[X, Y](xInitialQuantity, yInitialQuantity, xAddress, yAddress) _
    } yield instance(s)

  def apply[X <: Currency, Y <: Currency](
    xInitialQuantity: BigDecimal,
    yInitialQuantity: BigDecimal,
    xAddress: String,
    yAddress: String
  )(semaphore: Semaphore[IO]): LiquidityPool[X, Y] =
    new LiquidityPool(xInitialQuantity, yInitialQuantity, xAddress, yAddress)(semaphore)
}
