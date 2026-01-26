package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.transaction.RewardTransaction

trait RewardAcceptanceManager[F[_]] {
  def acceptRewardTxs(
    balances: SortedMap[Address, Balance],
    txs: SortedSet[RewardTransaction]
  ): (SortedMap[Address, Balance], SortedSet[RewardTransaction], SortedMap[Address, Balance])
}

object RewardAcceptanceManager {

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
