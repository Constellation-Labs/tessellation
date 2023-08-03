package org.tessellation.sdk.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.currency.schema.currency._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.security.signature.Signed
import org.tessellation.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

trait CurrencySnapshotAcceptanceManager[F[_]] {
  def accept(
    blocksForAcceptance: List[Signed[CurrencyBlock]],
    lastSnapshotContext: CurrencySnapshotContext,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]]
  ): F[
    (
      BlockAcceptanceResult[CurrencyBlock],
      SortedSet[RewardTransaction],
      CurrencySnapshotInfo,
      CurrencySnapshotStateProof
    )
  ]
}

object CurrencySnapshotAcceptanceManager {
  def make[F[_]: Async: KryoSerializer](
    blockAcceptanceManager: BlockAcceptanceManager[F, CurrencyBlock],
    collateral: Amount
  ) = new CurrencySnapshotAcceptanceManager[F] {

    def accept(
      blocksForAcceptance: List[Signed[CurrencyBlock]],
      lastSnapshotContext: CurrencySnapshotContext,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]]
    ) = for {
      initialTxRef <- TransactionReference.emptyCurrency(lastSnapshotContext.address)

      acceptanceResult <- acceptBlocks(
        blocksForAcceptance,
        lastSnapshotContext,
        lastActiveTips,
        lastDeprecatedTips,
        initialTxRef
      )

      transactionsRefs = lastSnapshotContext.snapshotInfo.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs

      acceptedTransactions = acceptanceResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

      rewards <- calculateRewardsFn(acceptedTransactions)

      (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
        lastSnapshotContext.snapshotInfo.balances ++ acceptanceResult.contextUpdate.balances,
        rewards
      )

      csi = CurrencySnapshotInfo(transactionsRefs, updatedBalancesByRewards)
      stateProof <- csi.stateProof

    } yield (acceptanceResult, acceptedRewardTxs, csi, stateProof)

    private def acceptBlocks(
      blocksForAcceptance: List[Signed[CurrencyBlock]],
      lastSnapshotContext: CurrencySnapshotContext,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      initialTxRef: TransactionReference
    ) = {
      val tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
      val context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.snapshotInfo.balances,
        lastSnapshotContext.snapshotInfo.lastTxRefs,
        tipUsages,
        collateral,
        initialTxRef
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)
    }

    private def acceptRewardTxs(
      balances: SortedMap[Address, Balance],
      txs: SortedSet[RewardTransaction]
    ): (SortedMap[Address, Balance], SortedSet[RewardTransaction]) =
      txs.foldLeft((balances, SortedSet.empty[RewardTransaction])) { (acc, tx) =>
        val (updatedBalances, acceptedTxs) = acc

        updatedBalances
          .getOrElse(tx.destination, Balance.empty)
          .plus(tx.amount)
          .map(balance => (updatedBalances.updated(tx.destination, balance), acceptedTxs + tx))
          .getOrElse(acc)
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
