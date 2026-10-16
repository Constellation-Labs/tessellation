package io.constellationnetwork.node.shared.infrastructure.snapshot

import scala.util.control.NoStackTrace

import io.constellationnetwork.schema.GlobalSnapshotInfo
import io.constellationnetwork.schema.balance.Amount

import eu.timepit.refined.types.numeric.NonNegLong

object CurrentDagSupply {
  final case class SupplyOverflow(total: BigInt)
      extends RuntimeException(s"Current DAG supply exceeds the supported amount range: $total")
      with NoStackTrace

  // Outstanding native DAG in the ledger: spendable balances, token-lock principal (which is where delegated stake
  // and node collateral live), native allow-spend escrow, and accrued delegation rewards not yet paid out.
  // Metagraph currencies and the mirrored token-lock balance projections are not DAG.
  def calculate(info: GlobalSnapshotInfo): Either[SupplyOverflow, Amount] = {
    val balances = info.balances.values.foldLeft(BigInt(0))((total, balance) => total + balance.value.value)
    val locks = info.activeTokenLocks.fold(BigInt(0))(_.values.foldLeft(BigInt(0)) { (total, records) =>
      records.foldLeft(total)((sum, record) => sum + record.value.amount.value.value)
    })
    val allowSpends = info.activeAllowSpends
      .flatMap(_.get(None))
      .fold(BigInt(0))(_.values.foldLeft(BigInt(0)) { (total, records) =>
        records.foldLeft(total)((sum, record) => sum + record.value.amount.value.value)
      })
    val rewards = info.activeDelegatedStakes.fold(BigInt(0))(_.values.foldLeft(BigInt(0)) { (total, records) =>
      records.foldLeft(total)((sum, record) => sum + record.rewards.value.value)
    })
    val pendingRewards = info.delegatedStakesWithdrawals.fold(BigInt(0))(_.values.foldLeft(BigInt(0)) { (total, records) =>
      records.foldLeft(total)((sum, record) => sum + record.rewards.value.value)
    })
    val total = balances + locks + allowSpends + rewards + pendingRewards

    Either.cond(total.isValidLong, total.toLong, SupplyOverflow(total)).flatMap { value =>
      NonNegLong.from(value).left.map(_ => SupplyOverflow(total)).map(Amount(_))
    }
  }
}
