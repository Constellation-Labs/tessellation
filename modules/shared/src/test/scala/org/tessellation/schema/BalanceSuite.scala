package org.tessellation.schema

import org.tessellation.schema.balance.{Amount, Balance}

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.FunSuite
import weaver.scalacheck.Checkers

object BalanceSuite extends FunSuite with Checkers {
  test("plus should add given amount to balance") {
    val balance = Balance(NonNegLong(1L))
    val amount = Amount(NonNegLong(2L))

    val newBalance = balance.plus(amount)

    val expected = Balance(NonNegLong(3L))

    exists(newBalance)(expect.same(_, expected))
  }

  test("plus returns Left when sum overflows the Long range") {
    val balance = Balance(NonNegLong(Long.MaxValue))
    val amount = Amount(NonNegLong(2L))

    verify(balance.plus(amount).isLeft)
  }

  test("minus should subtract given amount from balance") {
    val balance = Balance(NonNegLong(30L))
    val amount = Amount(NonNegLong(1L))

    val expected = Balance(NonNegLong(29L))

    exists(balance.minus(amount))(expect.same(_, expected))
  }

  test("minus returns Left when amount lowers balance below zero") {
    val balance = Balance(NonNegLong(1L))
    val amount = Amount(NonNegLong(30L))

    verify(balance.minus(amount).isLeft)
  }
}
