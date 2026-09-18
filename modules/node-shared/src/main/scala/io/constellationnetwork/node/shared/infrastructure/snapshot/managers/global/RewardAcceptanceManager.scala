package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.transaction.RewardTransaction

trait RewardAcceptanceManager[F[_]] {
  def acceptRewardTxs(
    balances: SortedMap[Address, Balance],
    txs: SortedSet[RewardTransaction]
  ): (SortedMap[Address, Balance], SortedSet[RewardTransaction], SortedMap[Address, Balance])
}

object RewardAcceptanceManager {

  /** Activated settlement must fail on overflow, never retire a withdrawal whose reward was silently skipped. */
  def acceptRewardTxsChecked(
    balances: SortedMap[Address, Balance],
    txs: SortedSet[RewardTransaction]
  ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedSet[RewardTransaction], SortedMap[Address, Balance])] =
    txs.toList.foldM((balances, SortedSet.empty[RewardTransaction], SortedMap.empty[Address, Balance])) {
      case ((updated, accepted, deltas), tx) =>
        updated.getOrElse(tx.destination, Balance.empty).plus(tx.amount).map { balance =>
          (updated.updated(tx.destination, balance), accepted + tx, deltas.updated(tx.destination, balance))
        }
    }

  def make[F[_]](): RewardAcceptanceManager[F] = new RewardAcceptanceManager[F] {

    def acceptRewardTxs(
      balances: SortedMap[Address, Balance],
      txs: SortedSet[RewardTransaction]
    ): (SortedMap[Address, Balance], SortedSet[RewardTransaction], SortedMap[Address, Balance]) =
      txs.foldLeft((balances, SortedSet.empty[RewardTransaction], SortedMap.empty[Address, Balance])) { (acc, tx) =>
        val (updatedBalances, acceptedTxs, balanceDeltas) = acc

        val updatedBalance = updatedBalances
          .getOrElse(tx.destination, Balance.empty)
          .plus(tx.amount)

        updatedBalance
          .map(balance =>
            (updatedBalances.updated(tx.destination, balance), acceptedTxs + tx, balanceDeltas.updated(tx.destination, balance))
          )
          .getOrElse(acc)
      }
  }
}
