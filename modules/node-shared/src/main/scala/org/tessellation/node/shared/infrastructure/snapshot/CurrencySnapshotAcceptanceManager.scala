package org.tessellation.node.shared.infrastructure.snapshot

import cats.data.Validated
import cats.effect.Async
import cats.kernel.Order
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.currency.schema.currency._
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
      CurrencySnapshotInfo,
      CurrencySnapshotStateProof
    )
  ]
}

object CurrencySnapshotAcceptanceManager {
  def make[F[_]: Async](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    collateral: Amount,
    messageValidator: CurrencyMessageValidator[F]
  ) = new CurrencySnapshotAcceptanceManager[F] {

    def accept(
      blocksForAcceptance: List[Signed[Block]],
      messagesForAcceptance: List[Signed[CurrencyMessage]],
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

      msgOrdering = Order
        .whenEqual[Signed[CurrencyMessage]](
          Order.whenEqual(Order.by(_.parentOrdinal), Order.reverse(Order.by(_.proofs.size))),
          Order[Signed[CurrencyMessage]]
        )
        .toOrdering

      (messagesForContextUpdate, messagesToAccept, messagesToReject) <-
        messagesForAcceptance
          .sorted(msgOrdering)
          .foldLeftM((lastMessages, List.empty[Signed[CurrencyMessage]], List.empty[Signed[CurrencyMessage]])) {
            case ((lastMsgs, toAdd, toReject), message) =>
              messageValidator.validate(message, lastMsgs, lastSnapshotContext.address).map {
                case Validated.Valid(_) =>
                  val updatedLastMsgs = lastMsgs.updated(message.messageType, message)
                  val updatedToAdd = message :: toAdd

                  (updatedLastMsgs, updatedToAdd, toReject)
                case Validated.Invalid(_) =>
                  val updatedToReject = message :: toReject

                  (lastMsgs, toAdd, updatedToReject)
              }
          }

      messagesAcceptanceResult = CurrencyMessagesAcceptanceResult(
        messagesForContextUpdate,
        messagesToAccept,
        messagesToReject
      )

      csi = CurrencySnapshotInfo(transactionsRefs, updatedBalancesByRewards, messagesForContextUpdate.some)
      stateProof <- csi.stateProof(snapshotOrdinal)

    } yield (acceptanceResult, messagesAcceptanceResult, acceptedRewardTxs, csi, stateProof)

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
