package org.tessellation.node.shared.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.currency.schema.currency._
import org.tessellation.currency.schema.feeTransaction.FeeTransaction
import org.tessellation.node.shared.domain.block.processing._
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.currencyMessage._
import org.tessellation.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import org.tessellation.security.Hasher
import org.tessellation.security.signature.Signed
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
    feeTransactionsForAcceptance: Option[SortedSet[Signed[FeeTransaction]]],
    lastSnapshotContext: CurrencySnapshotContext,
    snapshotOrdinal: SnapshotOrdinal,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]]
  )(implicit hasher: Hasher[F]): F[
    (
      BlockAcceptanceResult,
      CurrencyMessagesAcceptanceResult,
      SortedSet[RewardTransaction],
      Option[SortedSet[Signed[FeeTransaction]]],
      CurrencySnapshotInfo,
      CurrencySnapshotStateProof
    )
  ]
}

object CurrencySnapshotAcceptanceManager {
  def make[F[_]: Async](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    collateral: Amount
  ) = new CurrencySnapshotAcceptanceManager[F] {

    def accept(
      blocksForAcceptance: List[Signed[Block]],
      messagesForAcceptance: List[Signed[CurrencyMessage]],
      feeTransactionsForAcceptance: Option[SortedSet[Signed[FeeTransaction]]],
      lastSnapshotContext: CurrencySnapshotContext,
      snapshotOrdinal: SnapshotOrdinal,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]]
    )(implicit hasher: Hasher[F]): F[
      (
        BlockAcceptanceResult,
        CurrencyMessagesAcceptanceResult,
        SortedSet[RewardTransaction],
        Option[SortedSet[Signed[FeeTransaction]]],
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

      balancesAfterContextUpdate = lastSnapshotContext.snapshotInfo.balances ++ acceptanceResult.contextUpdate.balances

      (balancesAfterRewards, acceptedRewardTxs) = acceptRewardTxs(
        balancesAfterContextUpdate,
        rewards
      )

      (updatedBalances, acceptedFeeTxs) <- acceptFeeTxs(
        balancesAfterRewards,
        feeTransactionsForAcceptance
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

      csi = CurrencySnapshotInfo(transactionsRefs, updatedBalances)
      stateProof <- csi.stateProof(snapshotOrdinal)

    } yield (acceptanceResult, messagesAcceptanceResult, acceptedRewardTxs, acceptedFeeTxs, csi, stateProof)

    private def acceptBlocks(
      blocksForAcceptance: List[Signed[Block]],
      lastSnapshotContext: CurrencySnapshotContext,
      snapshotOrdinal: SnapshotOrdinal,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      initialTxRef: TransactionReference
    )(implicit hasher: Hasher[F]) = {
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

    private def acceptFeeTxs(
      balances: SortedMap[Address, Balance],
      maybeTxs: Option[SortedSet[Signed[FeeTransaction]]]
    ): F[(SortedMap[Address, Balance], Option[SortedSet[Signed[FeeTransaction]]])] =
      maybeTxs match {
        case None => (balances, maybeTxs).pure[F]
        case Some(txs) =>
          val feeReferredAddresses = txs.flatMap(tx => Set(tx.value.source, tx.value.destination))
          val feeReferredBalances = feeReferredAddresses.foldLeft(SortedMap.empty[Address, Long]) {
            case (acc, address) =>
              acc.updated(address, balances.getOrElse(address, Balance.empty).value.value)
          }
          val updatedFeeReferredBalances = txs
            .foldLeft(feeReferredBalances) {
              case (balances, tx) =>
                balances
                  .updatedWith(tx.source)(existing => (existing.getOrElse(Balance.empty.value.value) - tx.amount.value).some)
                  .updatedWith(tx.destination)(existing => (existing.getOrElse(Balance.empty.value.value) + tx.amount.value).some)
            }

          updatedFeeReferredBalances.toList
            .foldLeftM(SortedMap.empty[Address, Balance]) {
              case (acc, (address, balance)) =>
                NonNegLong
                  .from(balance)
                  .map(Balance(_))
                  .map(acc.updated(address, _))
                  .leftMap(e => new ArithmeticException(s"Unexpected state when applying fee transactions: $e"))
                  .liftTo[F]
            }
            .map { updates =>
              (balances ++ updates, txs.some)
            }
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
