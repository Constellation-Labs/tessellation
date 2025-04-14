package org.tessellation.dag.l0.infrastructure.metrics

import org.tessellation.currency.schema.currency.CurrencySnapshotInfo
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

/** Extension methods for CurrencySnapshotInfo to provide metrics and analysis functionality. This is used by GlobalSnapshotMetricsAnalyzer
  * to calculate statistics about balance maps.
  */
object CurrencySnapshotInfoOps {
  val LargeBalanceThreshold: Long = 100_000_000_000_000L // 1 * 1e6 * 1e8
  val SmallBalanceThreshold: Long = 10_000_000L // 1 * 1e8

  implicit class CurrencySnapshotInfoOpsImpl(val info: CurrencySnapshotInfo) extends AnyVal {

    /** Analyzes the balance map and returns summary statistics.
      *
      * @return
      *   A tuple of (totalSize, totalValue, largeBalancesCount)
      *   - totalSize: The total number of addresses in the balance map
      *   - totalValue: The sum of all balance values in the map
      *   - largeBalancesCount: The number of addresses with balances over the large balance threshold
      */
    def analyzeBalanceMapSize: (Int, Long, Int, Int) = {
      val totalSize = info.balances.size
      val totalValue = info.balances.values.map(_.value.value).sum
      val largeBalancesCount = info.balances.count { case (_, balance) => balance.value.value >= LargeBalanceThreshold }
      val smallBalancesCount = info.balances.count { case (_, balance) => balance.value.value <= SmallBalanceThreshold }
      (totalSize, totalValue, largeBalancesCount, smallBalancesCount)
    }

    /** Returns a filtered map of addresses with large balances
      */
    def getLargeBalances: collection.Map[Address, Balance] =
      info.balances.filter { case (_, balance) => balance.value.value > LargeBalanceThreshold }
  }
}
