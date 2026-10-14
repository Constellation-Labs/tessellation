package io.constellationnetwork.node.shared.infrastructure.snapshot

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global.RewardAcceptanceManager
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}

import cats.syntax.all._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import weaver.SimpleIOSuite

object RewardTransactionAggregationSuite extends SimpleIOSuite {
  private val operator = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  private val treasury = Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd")

  private def reward(address: Address, amount: Long): RewardTransaction =
    RewardTransaction(address, TransactionAmount(PosLong.unsafeFrom(amount)))

  pureTest("identical rewards from independent categories are all paid to the shared destination") {
    expect(
      RewardTransactionAggregation.combine(List(reward(operator, 100L), reward(operator, 100L), reward(operator, 100L))) ==
        Right(SortedSet(reward(operator, 300L)))
    )
  }

  pureTest("distinct categories aggregate per destination without moving value between recipients") {
    val transactions = List(reward(operator, 7L), reward(treasury, 50L), reward(operator, 11L), reward(treasury, 3L))
    val expected = Right(SortedSet(reward(operator, 18L), reward(treasury, 53L)))

    expect(RewardTransactionAggregation.combine(transactions) == expected)
      .and(expect(RewardTransactionAggregation.combine(transactions.reverse) == expected))
  }

  pureTest("an empty reward epoch produces no transactions") {
    expect(RewardTransactionAggregation.combine(List.empty) == Right(SortedSet.empty[RewardTransaction]))
  }

  pureTest("the maximum representable destination payout is accepted") {
    expect(
      RewardTransactionAggregation.combine(List(reward(operator, Long.MaxValue - 1L), reward(operator, 1L))) ==
        Right(SortedSet(reward(operator, Long.MaxValue)))
    )
  }

  pureTest("an overflowing destination fails the entire batch with the exact amount and recipient") {
    expect(
      RewardTransactionAggregation.combine(List(reward(operator, Long.MaxValue), reward(treasury, 10L), reward(operator, 1L))) ==
        Left(RewardTransactionAggregation.RewardOverflow(operator, BigInt(Long.MaxValue) + 1))
    )
  }

  pureTest("overflow that would wrap to a positive Long cannot manufacture a smaller payout") {
    expect(
      RewardTransactionAggregation.combine(List.fill(3)(reward(operator, Long.MaxValue))) ==
        Left(RewardTransactionAggregation.RewardOverflow(operator, BigInt(Long.MaxValue) * 3))
    )
  }

  pureTest("a withdrawal equal to an operator's combined payout is still credited in full at the payout boundary") {
    // static 1000 + commission 350 already arrive as one 1350 operator transaction; a withdrawal to the same wallet
    // of exactly 1350 would be lost in the SortedSet union the legacy payout path performs
    val operatorPayout = SortedSet(reward(operator, 1350L))
    val withdrawalPayout = SortedSet(reward(operator, 1350L))
    val balances = SortedMap(operator -> Balance(0L))

    val legacy = RewardAcceptanceManager.acceptRewardTxsChecked(balances, withdrawalPayout ++ operatorPayout)
    val combined = RewardTransactionAggregation
      .combine(withdrawalPayout.toList ++ operatorPayout.toList)
      .leftWiden[Throwable]
      .flatMap(RewardAcceptanceManager.acceptRewardTxsChecked(balances, _))

    expect(legacy.map(_._1(operator)) == Right(Balance(1350L)))
      .and(expect(combined.map(_._1(operator)) == Right(Balance(2700L))))
      .and(expect(combined.map(_._2) == Right(SortedSet(reward(operator, 2700L)))))
  }
}
