package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.Validated
import cats.effect.Async
import cats.kernel.Order
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.currencyMessage._
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection._

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

      acceptedTransactions = acceptanceResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

      transactionsRefs = acceptTransactionRefs(
        lastSnapshotContext.snapshotInfo.lastTxRefs,
        acceptanceResult.contextUpdate.lastTxRefs,
        acceptedTransactions
      )

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
              messageValidator.validate(message, lastMsgs, lastSnapshotContext.address, Map.empty).map {
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

      csi = CurrencySnapshotInfo(
        transactionsRefs,
        updatedBalancesByRewards,
        Option.when(messagesForContextUpdate.nonEmpty)(messagesForContextUpdate),
        None,
        None,
        SortedMap.empty[Address, SortedSet[Signed[TokenLock]]].some
      )
      stateProof <- csi.stateProof(snapshotOrdinal)

    } yield (acceptanceResult, messagesAcceptanceResult, acceptedRewardTxs, csi, stateProof)

    private def acceptTransactionRefs(
      lastTxRefs: SortedMap[Address, TransactionReference],
      lastTxRefsContextUpdate: Map[Address, TransactionReference],
      acceptedTransactions: SortedSet[Signed[Transaction]]
    ): SortedMap[Address, TransactionReference] = {
      val updatedRefs = lastTxRefs ++ lastTxRefsContextUpdate
      val newDestinationAddresses = acceptedTransactions.map(_.destination) -- updatedRefs.keySet
      updatedRefs ++ newDestinationAddresses.toList.map(_ -> TransactionReference.empty)
    }

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
