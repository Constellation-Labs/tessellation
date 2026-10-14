package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.transaction.{RewardTransaction, TransactionAmount}

import eu.timepit.refined.types.numeric.PosLong

object RewardTransactionAggregation {
  final case class RewardOverflow(address: Address, amount: BigInt)
      extends RuntimeException(s"Combined reward for $address exceeds the supported amount range: $amount")
      with NoStackTrace

  def combine(rewards: List[RewardTransaction]): Either[RewardOverflow, SortedSet[RewardTransaction]] =
    rewards
      .groupMapReduce(_.destination)(r => BigInt(r.amount.value.value))(_ + _)
      .toList
      .traverse {
        case (address, value) =>
          Either.cond(value.isValidLong, value.toLong, RewardOverflow(address, value)).flatMap { long =>
            PosLong
              .from(long)
              .leftMap(_ => RewardOverflow(address, value))
              .map(amount => RewardTransaction(address, TransactionAmount(amount)))
          }
      }
      .map(SortedSet.from(_))

  def credit(
    balances: SortedMap[Address, Balance],
    rewards: SortedSet[RewardTransaction]
  ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedSet[RewardTransaction])] =
    rewards.toList
      .foldLeftM(balances) { (current, reward) =>
        current
          .getOrElse(reward.destination, Balance.empty)
          .plus(reward.amount)
          .map(balance => current.updated(reward.destination, balance))
      }
      .map(_ -> rewards)
}
