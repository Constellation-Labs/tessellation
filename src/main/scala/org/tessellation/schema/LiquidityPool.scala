package org.tessellation.schema

import cats.effect.IO
import cats.effect.concurrent.Ref

abstract class LiquidityPool[A <: Currency, B <: Currency](
  balanceA: Long,
  balanceB: Long,
  addressA: String,
  addressB: String
) {
  protected val poolBalances: Ref[IO, (Long, Long)] = Ref.unsafe((balanceA, balanceB))

  protected def getBalanceA: IO[Long] = poolBalances.get.map { case (a, _) => a }

  protected def getBalanceB: IO[Long] = poolBalances.get.map { case (_, b) => b }

  protected def exchangeAForB(amount: Long): IO[Long] = poolBalances.modify {
    case (a, b) =>
      val amountOfBToReturn = (amount * balanceB) / balanceA
      ((a + amount, b - amountOfBToReturn), amountOfBToReturn)
  }

  protected def exchangeBForA(amount: Long): IO[Long] = poolBalances.modify {
    case (a, b) =>
      val amountOfAToReturn = (amount * balanceA) / balanceB
      ((a - amountOfAToReturn, b + amount), amountOfAToReturn)
  }
}
