package org.tessellation.node.shared.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.currency.schema.currency._
import org.tessellation.node.shared.domain.block.processing._
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.currencyMessage._
import org.tessellation.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import org.tessellation.security.signature.Signed
import org.tessellation.security.{HashSelect, Hasher}
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

case class CurrencyMessagesAcceptanceResult(
  contextUpdate: SortedMap[MessageType, Signed[CurrencyMessage]],
  accepted: List[Signed[CurrencyMessage]],
  notAccepted: List[Signed[CurrencyMessage]]
)

trait CurrencySnapshotAcceptanceManager[F[_]] {
  def accept(
    blocksForAcceptance: List[Signed[Block]],
    messagesForAcceptance: List[Signed[CurrencyMessage]],
    lastSnapshotContext: CurrencySnapshotContext,
    snapshotOrdinal: SnapshotOrdinal,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]]
  ): F[
    (
      BlockAcceptanceResult,
      CurrencyMessagesAcceptanceResult,
      SortedSet[RewardTransaction],
      CurrencySnapshotInfo,
      CurrencySnapshotStateProof
    )
  ]
}

object CurrencySnapshotAcceptanceManager {
  def make[F[_]: Async: Hasher](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    collateral: Amount,
    hashSelect: HashSelect
  ) = new CurrencySnapshotAcceptanceManager[F] {

    def accept(
      blocksForAcceptance: List[Signed[Block]],
      messagesForAcceptance: List[Signed[CurrencyMessage]],
      lastSnapshotContext: CurrencySnapshotContext,
      snapshotOrdinal: SnapshotOrdinal,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]]
    ): F[
      (
        BlockAcceptanceResult,
        CurrencyMessagesAcceptanceResult,
        SortedSet[RewardTransaction],
        CurrencySnapshotInfo,
        CurrencySnapshotStateProof
      )
    ] = for {
      initialTxRef <- TransactionReference.emptyCurrency(lastSnapshotContext.address)

      acceptanceResult <- acceptBlocks(
        blocksForAcceptance,
        lastSnapshotContext,
        snapshotOrdinal,
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

      lastMessages = lastSnapshotContext.snapshotInfo.lastMessages.getOrElse(SortedMap.empty[MessageType, Signed[CurrencyMessage]])

      messagesToAccept = messagesForAcceptance
        .groupBy(_.messageType)
        .view
        .mapValues { messages =>
          val sortedMessages = messages.sortBy(_.parentOrdinal)

          sortedMessages
            .foldLeft(List.empty[Signed[CurrencyMessage]]) { (acc, curr) =>
              lastMessages.get(curr.messageType) match {
                case Some(lastMsg) if curr.parentOrdinal <= lastMsg.parentOrdinal => acc
                case _ if acc.isEmpty || acc.head.ordinal == curr.parentOrdinal   => curr :: acc
                case _                                                            => acc
              }
            }
        }
        .filter {
          case (_, messages) => messages.nonEmpty
        }
        .toMap

      messagesToReject = messagesForAcceptance.filterNot(messagesToAccept.values.toList.flatten.contains)

      messagesForContextUpdate = (lastMessages.view.mapValues(List(_)).toMap |+| messagesToAccept).view
        .mapValues(_.maxByOption(_.ordinal))
        .collect {
          case (k, Some(v)) => k -> v
        }
        .toSortedMap

      messagesAcceptanceResult = CurrencyMessagesAcceptanceResult(
        messagesForContextUpdate,
        messagesToAccept.values.toList.flatten,
        messagesToReject
      )

      csi = CurrencySnapshotInfo(transactionsRefs, updatedBalancesByRewards)
      stateProof <- csi.stateProof(snapshotOrdinal, hashSelect)

    } yield (acceptanceResult, messagesAcceptanceResult, acceptedRewardTxs, csi, stateProof)

    private def acceptBlocks(
      blocksForAcceptance: List[Signed[Block]],
      lastSnapshotContext: CurrencySnapshotContext,
      snapshotOrdinal: SnapshotOrdinal,
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

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context, snapshotOrdinal)
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
