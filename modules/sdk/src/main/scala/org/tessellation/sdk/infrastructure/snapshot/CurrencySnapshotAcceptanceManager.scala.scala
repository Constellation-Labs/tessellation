package org.tessellation.sdk.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.functor._

import scala.collection.immutable.SortedSet

import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencySnapshotInfo, CurrencyTransaction}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.balance.Amount
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

trait CurrencySnapshotAcceptanceManager[F[_]] {
  def accept(
    blocksForAcceptance: List[Signed[CurrencyBlock]],
    lastSnapshotContext: CurrencySnapshotInfo,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip]
  ): F[
    (
      BlockAcceptanceResult[CurrencyBlock],
      CurrencySnapshotInfo
    )
  ]
}

object CurrencySnapshotAcceptanceManager {
  def make[F[_]: Async: KryoSerializer](
    blockAcceptanceManager: BlockAcceptanceManager[F, CurrencyTransaction, CurrencyBlock],
    collateral: Amount
  ) = new CurrencySnapshotAcceptanceManager[F] {

    def accept(
      blocksForAcceptance: List[Signed[CurrencyBlock]],
      lastSnapshotContext: CurrencySnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip]
    ) = for {
      acceptanceResult <- acceptBlocks(blocksForAcceptance, lastSnapshotContext, lastActiveTips, lastDeprecatedTips)

      transactionsRefs = lastSnapshotContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs

      updatedBalances = lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances

    } yield
      (
        acceptanceResult,
        CurrencySnapshotInfo(
          transactionsRefs,
          updatedBalances
        )
      )

    private def acceptBlocks(
      blocksForAcceptance: List[Signed[CurrencyBlock]],
      lastSnapshotContext: CurrencySnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip]
    ) = {
      val tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
      val context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTxRefs,
        tipUsages,
        collateral
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)
    }

    def getTipsUsages(
      lastActive: Set[ActiveTip],
      lastDeprecated: Set[DeprecatedTip]
    ): Map[BlockReference, NonNegLong] = {
      val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
      val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

      activeTipsUsages ++ deprecatedTipsUsages
    }

  }

}
