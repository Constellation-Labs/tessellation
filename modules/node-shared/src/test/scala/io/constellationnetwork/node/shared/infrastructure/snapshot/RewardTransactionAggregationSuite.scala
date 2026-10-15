package io.constellationnetwork.node.shared.infrastructure.snapshot

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{AmountOverflow, Balance}
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.SimpleIOSuite

object RewardTransactionAggregationSuite extends SimpleIOSuite {
  private val operator = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  private val treasury = Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd")

  private def reward(address: Address, amount: Long): RewardTransaction =
    RewardTransaction(address, TransactionAmount(PosLong.unsafeFrom(amount)))

  pureTest("identical rewards from independent categories are all paid to the shared destination") {
    val validatorReward = reward(operator, 100L)
    val commissionReward = reward(operator, 100L)
    val withdrawalPayout = reward(operator, 100L)

    expect(
      RewardTransactionAggregation.combine(List(validatorReward, commissionReward, withdrawalPayout)) ==
        Right(SortedSet(reward(operator, 300L))),
      "equal validator, commission, and withdrawal transactions must not collapse when converted to a set"
    )
  }

  pureTest("distinct categories aggregate per destination without moving value between recipients") {
    val transactions = List(reward(operator, 7L), reward(treasury, 50L), reward(operator, 11L), reward(treasury, 3L))
    val expected = Right(SortedSet(reward(operator, 18L), reward(treasury, 53L)))

    expect(RewardTransactionAggregation.combine(transactions) == expected, "each recipient receives its complete entitlement")
      .and(expect(RewardTransactionAggregation.combine(transactions.reverse) == expected, "category ordering cannot change payouts"))
  }

  pureTest("an empty reward epoch produces no transactions") {
    expect(
      RewardTransactionAggregation.combine(List.empty) == Right(SortedSet.empty[RewardTransaction]),
      "no rewards should produce no output transactions"
    )
  }

  pureTest("the maximum representable destination payout is accepted") {
    val transactions = List(reward(operator, Long.MaxValue - 1L), reward(operator, 1L))

    expect(
      RewardTransactionAggregation.combine(transactions) == Right(SortedSet(reward(operator, Long.MaxValue))),
      "an exact Long.MaxValue aggregate must remain payable"
    )
  }

  pureTest("an overflowing destination fails the entire batch with the exact amount and recipient") {
    val transactions = List(reward(operator, Long.MaxValue), reward(treasury, 10L), reward(operator, 1L))

    expect(
      RewardTransactionAggregation.combine(transactions) ==
        Left(RewardTransactionAggregation.RewardOverflow(operator, BigInt(Long.MaxValue) + 1)),
      "overflow must return a typed error rather than truncating a payout or silently paying only other recipients"
    )
  }

  pureTest("overflow that would wrap to a positive Long cannot manufacture a smaller payout") {
    val transactions = List.fill(3)(reward(operator, Long.MaxValue))

    expect(
      RewardTransactionAggregation.combine(transactions) ==
        Left(RewardTransactionAggregation.RewardOverflow(operator, BigInt(Long.MaxValue) * 3)),
      "BigInt must preserve the aggregate even when unchecked Long addition would appear positive"
    )
  }

  pureTest("credit pays existing and new recipients while preserving unrelated balances and accepted rewards") {
    val unaffected = Address("DAG5bvqxSJmbWVwcKWEU7nb3sgTnN1QZMPi4F8Cc")
    val balances = SortedMap(operator -> Balance(100L), unaffected -> Balance(999L))
    val rewards = SortedSet(reward(operator, 17L), reward(treasury, 23L))
    val expectedBalances = SortedMap(operator -> Balance(117L), treasury -> Balance(23L), unaffected -> Balance(999L))

    expect(
      RewardTransactionAggregation.credit(balances, rewards) == Right((expectedBalances, rewards)),
      "every reward must be credited exactly once, including a destination without a previous balance"
    )
  }

  pureTest("credit rejects the entire payout when an existing destination balance would overflow") {
    val balances = SortedMap(operator -> Balance(NonNegLong.unsafeFrom(Long.MaxValue)), treasury -> Balance(50L))
    val rewards = SortedSet(reward(operator, 1L), reward(treasury, 23L))

    expect(
      RewardTransactionAggregation.credit(balances, rewards) == Left(AmountOverflow),
      "an individually valid reward must not be silently dropped or return partially credited balances when the recipient is full"
    )
  }
}
