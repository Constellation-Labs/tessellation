package io.constellationnetwork.node.shared.infrastructure.snapshot

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}

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
}
