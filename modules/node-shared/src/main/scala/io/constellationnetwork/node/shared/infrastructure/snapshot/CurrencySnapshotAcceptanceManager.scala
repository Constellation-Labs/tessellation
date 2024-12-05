package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Order
import cats.data.Validated
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSnapshotSync
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.tokenlock.block.{
  TokenLockBlockAcceptanceContext,
  TokenLockBlockAcceptanceManager,
  TokenLockBlockAcceptanceResult
}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SharedArtifact, TokenUnlock}
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.currencyMessage._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.tokenLock.TokenLockAmount.toAmount
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockBlock, TokenLockReference}
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

case class GlobalSnapshotSyncAcceptanceResult(
  contextUpdate: SortedMap[PeerId, Signed[GlobalSnapshotSync]],
  accepted: List[Signed[GlobalSnapshotSync]],
  notAccepted: List[Signed[GlobalSnapshotSync]]
)

case class CurrencySnapshotAcceptanceResult(
  block: BlockAcceptanceResult,
  tokenLockBlock: TokenLockBlockAcceptanceResult,
  messages: CurrencyMessagesAcceptanceResult,
  globalSnapshotSync: GlobalSnapshotSyncAcceptanceResult,
  rewards: SortedSet[RewardTransaction],
  sharedArtifacts: SortedSet[SharedArtifact],
  info: CurrencySnapshotInfo,
  stateProof: CurrencySnapshotStateProof
)

trait CurrencySnapshotAcceptanceManager[F[_]] {
  def accept(
    blocksForAcceptance: List[Signed[Block]],
    tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
    messagesForAcceptance: List[Signed[CurrencyMessage]],
    globalSnapshotSyncsForAcceptance: List[Signed[GlobalSnapshotSync]],
    sharedArtifactsForAcceptance: SortedSet[SharedArtifact],
    lastSnapshotContext: CurrencySnapshotContext,
    snapshotOrdinal: SnapshotOrdinal,
    epochProgress: EpochProgress,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
    facilitators: Set[PeerId]
  )(implicit hasher: Hasher[F]): F[CurrencySnapshotAcceptanceResult]
}

object CurrencySnapshotAcceptanceManager {
  def make[F[_]: Async](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    collateral: Amount,
    messageValidator: CurrencyMessageValidator[F],
    globalSnapshotSyncValidator: GlobalSnapshotSyncValidator[F]
  ) = new CurrencySnapshotAcceptanceManager[F] {
    def accept(
      blocksForAcceptance: List[Signed[Block]],
      tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
      messagesForAcceptance: List[Signed[CurrencyMessage]],
      globalSnapshotSyncsForAcceptance: List[Signed[GlobalSnapshotSync]],
      sharedArtifactsForAcceptance: SortedSet[SharedArtifact],
      lastSnapshotContext: CurrencySnapshotContext,
      snapshotOrdinal: SnapshotOrdinal,
      epochProgress: EpochProgress,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
      facilitators: Set[PeerId]
    )(implicit hasher: Hasher[F]): F[CurrencySnapshotAcceptanceResult] = for {
      initialTxRef <- TransactionReference.emptyCurrency(lastSnapshotContext.address)
      tokenLockInitialTxRef <- TokenLockReference.emptyCurrency(lastSnapshotContext.address)

      acceptanceBlocksResult <- acceptBlocks(
        blocksForAcceptance,
        lastSnapshotContext,
        snapshotOrdinal,
        lastActiveTips,
        lastDeprecatedTips,
        initialTxRef
      )

      acceptanceTokenLockBlocksResult <- acceptTokenLockBlocks(
        tokenLockBlocksForAcceptance,
        lastSnapshotContext,
        snapshotOrdinal,
        tokenLockInitialTxRef
      )

      acceptedTransactions = acceptanceBlocksResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

      transactionsRefs = acceptTransactionRefs(
        lastSnapshotContext.snapshotInfo.lastTxRefs,
        acceptanceBlocksResult.contextUpdate.lastTxRefs,
        acceptedTransactions
      )

      tokenLockRefs = acceptTokenLockRefs(
        lastSnapshotContext.snapshotInfo.lastTokenLockRefsProof.getOrElse(SortedMap.empty[Address, TokenLockReference]),
        acceptanceTokenLockBlocksResult.contextUpdate.lastTokenLocksRefs
      )

      rewards <- calculateRewardsFn(acceptedTransactions)

      (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
        lastSnapshotContext.snapshotInfo.balances ++ acceptanceBlocksResult.contextUpdate.balances,
        rewards
      )

      acceptedSharedArtifacts = acceptSharedArtifacts(sharedArtifactsForAcceptance)

      messagesAcceptanceResult <- acceptMessages(
        lastSnapshotContext.snapshotInfo.lastMessages,
        messagesForAcceptance,
        lastSnapshotContext.address
      )

      globalSnapshotSyncAcceptanceResult <- acceptGlobalSnapshotSyncs(
        lastSnapshotContext.snapshotInfo.globalSnapshotSyncView,
        globalSnapshotSyncsForAcceptance,
        lastSnapshotContext.address,
        facilitators
      )

      incomingTokenLocks = acceptanceTokenLockBlocksResult.accepted.flatMap { tokenLockBlock =>
        tokenLockBlock.value.tokenLocks.toSortedSet
      }.toSortedSet

      activeTokenLocks = lastSnapshotContext.snapshotInfo.activeTokenLocks

      tokenLocksReferences <-
        (incomingTokenLocks.toList ++ activeTokenLocks.toList.flatMap(_.values).flatten)
          .traverse(tl => TokenLockReference.of(tl))

      tokenUnlocks = acceptedSharedArtifacts.collect {
        case tokenUnlock: TokenUnlock => tokenUnlock
      }

      acceptedTokenLocks = acceptTokenLocks(
        incomingTokenLocks,
        EpochProgress.MinValue // TODO: Get last sync global snapshot epoch progress
      )

      acceptedTokenUnlocks = acceptTokenUnlocks(
        tokenUnlocks,
        tokenLocksReferences
      )

      updatedActiveTokenLocks <- updateActiveTokenLocks(
        activeTokenLocks,
        acceptedTokenLocks,
        acceptedTokenUnlocks
      )

      updatedBalancesByTokenLocks = updateBalancesByTokenLocks(
        acceptedTokenLocks,
        acceptedTokenUnlocks,
        updatedBalancesByRewards
      )

      csi = CurrencySnapshotInfo(
        transactionsRefs,
        updatedBalancesByTokenLocks,
        Option.when(messagesAcceptanceResult.contextUpdate.nonEmpty)(messagesAcceptanceResult.contextUpdate),
        None,
        None,
        None,
        globalSnapshotSyncAcceptanceResult.contextUpdate.some,
        tokenLockRefs.some,
        updatedActiveTokenLocks
      )

      stateProof <- csi.stateProof(snapshotOrdinal)

    } yield
      CurrencySnapshotAcceptanceResult(
        acceptanceBlocksResult,
        acceptanceTokenLockBlocksResult,
        messagesAcceptanceResult,
        globalSnapshotSyncAcceptanceResult,
        acceptedRewardTxs,
        acceptedSharedArtifacts,
        csi,
        stateProof
      )

    private def acceptMessages(
      lastContextMessages: Option[SortedMap[MessageType, Signed[CurrencyMessage]]],
      messagesForAcceptance: List[Signed[CurrencyMessage]],
      metagraphId: Address
    )(implicit hs: Hasher[F]) = {
      val msgOrdering = Order
        .whenEqual[Signed[CurrencyMessage]](
          Order.whenEqual(Order.by(_.parentOrdinal), Order.reverse(Order.by(_.proofs.size))),
          Order[Signed[CurrencyMessage]]
        )
        .toOrdering

      messagesForAcceptance
        .sorted(msgOrdering)
        .foldLeftM(
          (
            lastContextMessages.getOrElse(SortedMap.empty[MessageType, Signed[CurrencyMessage]]),
            List.empty[Signed[CurrencyMessage]],
            List.empty[Signed[CurrencyMessage]]
          )
        ) {
          case ((lastMsgs, toAdd, toReject), message) =>
            messageValidator.validate(message, lastMsgs, metagraphId, Map.empty).map {
              case Validated.Valid(_) =>
                val updatedLastMsgs = lastMsgs.updated(message.messageType, message)
                val updatedToAdd = message :: toAdd

                (updatedLastMsgs, updatedToAdd, toReject)
              case Validated.Invalid(_) =>
                val updatedToReject = message :: toReject

                (lastMsgs, toAdd, updatedToReject)
            }
        }
        .map { case (contextUpdate, toAdd, toReject) => CurrencyMessagesAcceptanceResult(contextUpdate, toAdd, toReject) }

    }

    private def acceptGlobalSnapshotSyncs(
      lastGlobalSnapshotSyncView: Option[SortedMap[PeerId, Signed[GlobalSnapshotSync]]],
      globalSnapshotSyncsForAcceptance: List[Signed[GlobalSnapshotSync]],
      metagraphId: Address,
      facilitators: Set[PeerId]
    )(implicit hs: Hasher[F]) = {
      val ordering = Order
        .whenEqual[Signed[GlobalSnapshotSync]](
          Order.by(_.session),
          Order.whenEqual(
            Order[SnapshotOrdinal].contramap(_.ordinal),
            Order[Signed[GlobalSnapshotSync]]
          )
        )
        .toOrdering

      globalSnapshotSyncsForAcceptance.partitionEitherM { sync =>
        globalSnapshotSyncValidator
          .validate(sync, metagraphId, facilitators)
          .map(_.toEither.leftMap(_ => sync))
      }.map {
        case (toReject, toAdd) =>
          val newestSyncs = toAdd.groupBy(_.proofs.head.id.toPeerId).collect {
            case (peerId, syncs) if syncs.nonEmpty => peerId -> syncs.max(ordering)
          }
          val lastGlobalSnapshotSyncs = lastGlobalSnapshotSyncView.getOrElse(
            SortedMap.empty[PeerId, Signed[GlobalSnapshotSync]]
          )
          GlobalSnapshotSyncAcceptanceResult(lastGlobalSnapshotSyncs ++ newestSyncs, toAdd, toReject)
      }
    }

    private def acceptTransactionRefs(
      lastTxRefs: SortedMap[Address, TransactionReference],
      lastTxRefsContextUpdate: Map[Address, TransactionReference],
      acceptedTransactions: SortedSet[Signed[Transaction]]
    ): SortedMap[Address, TransactionReference] = {
      val updatedRefs = lastTxRefs ++ lastTxRefsContextUpdate
      val newDestinationAddresses = acceptedTransactions.map(_.destination) -- updatedRefs.keySet
      updatedRefs ++ newDestinationAddresses.toList.map(_ -> TransactionReference.empty)
    }

    private def acceptTokenLockRefs(
      lastTxRefs: SortedMap[Address, TokenLockReference],
      lastTxRefsContextUpdate: Map[Address, TokenLockReference]
    ): SortedMap[Address, TokenLockReference] = {
      val updatedRefs = lastTxRefs ++ lastTxRefsContextUpdate
      updatedRefs
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

    private def acceptTokenLockBlocks(
      tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
      lastSnapshotContext: CurrencySnapshotContext,
      snapshotOrdinal: SnapshotOrdinal,
      initialTxRef: TokenLockReference
    )(implicit hasher: Hasher[F]) = {
      val context = TokenLockBlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.snapshotInfo.balances,
        lastSnapshotContext.snapshotInfo.lastTokenLockRefsProof.getOrElse(SortedMap.empty),
        collateral,
        initialTxRef
      )

      tokenLockBlockAcceptanceManager.acceptBlocksIteratively(tokenLockBlocksForAcceptance, context, snapshotOrdinal)
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

    private def acceptSharedArtifacts(
      sharedArtifactsForAcceptance: SortedSet[SharedArtifact]
    ): SortedSet[SharedArtifact] =
      sharedArtifactsForAcceptance

    private def acceptTokenLocks(
      incomingTokenLocks: SortedSet[Signed[TokenLock]],
      lastSyncGlobalSnapshotEpochProgress: EpochProgress
    ): SortedSet[Signed[TokenLock]] =
      incomingTokenLocks.filter(itl => itl.unlockEpoch >= lastSyncGlobalSnapshotEpochProgress)

    private def acceptTokenUnlocks(
      incomingTokenUnlocks: SortedSet[TokenUnlock],
      activeTokenLocksReferences: List[TokenLockReference]
    ): SortedSet[TokenUnlock] =
      incomingTokenUnlocks.filter { itu =>
        activeTokenLocksReferences.contains(itu.lockReference)
      }

    private def updateActiveTokenLocks(
      maybeActiveTokenLocks: Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]],
      acceptedTokenLocks: SortedSet[Signed[TokenLock]],
      acceptedTokenUnlocks: SortedSet[TokenUnlock]
    )(implicit hasher: Hasher[F]): F[Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]] =
      if (acceptedTokenLocks.isEmpty && acceptedTokenUnlocks.isEmpty) {
        maybeActiveTokenLocks.pure
      } else {
        val incomingTokenLocks = acceptedTokenLocks.groupBy(_.source).toSortedMap
        val activeTokenLocks = maybeActiveTokenLocks.getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])

        val allTokenLocks = (incomingTokenLocks.keySet ++ activeTokenLocks.keySet).toList.map { address =>
          address -> (incomingTokenLocks.getOrElse(address, SortedSet.empty[Signed[TokenLock]]) ++ activeTokenLocks.getOrElse(
            address,
            SortedSet.empty[Signed[TokenLock]]
          ))
        }.toSortedMap

        if (acceptedTokenUnlocks.isEmpty) {
          allTokenLocks.some.pure
        } else {
          allTokenLocks.toList.traverse {
            case (address, set) =>
              set.toList.filterA { signedTokenLock =>
                TokenLockReference.of(signedTokenLock).map { tokenLockReference =>
                  acceptedTokenUnlocks.forall(_.lockReference =!= tokenLockReference)
                }
              }.map(filteredSet => address -> filteredSet.toSortedSet)
          }.map(_.toSortedMap.some)
        }
      }

    private def updateBalancesByTokenLocks(
      acceptedTokenLocks: SortedSet[Signed[TokenLock]],
      acceptedTokenUnlocks: SortedSet[TokenUnlock],
      balances: SortedMap[Address, Balance]
    ): SortedMap[Address, Balance] =
      if (acceptedTokenLocks.isEmpty && acceptedTokenUnlocks.isEmpty) {
        balances
      } else {
        val balancesAfterTokenLocks = acceptedTokenLocks.foldLeft(balances) {
          case (accBalances, signedTokenLock) =>
            val tokenLock = signedTokenLock.value
            val sourceAddress = tokenLock.source
            val lastAddressBalance = accBalances.getOrElse(sourceAddress, Balance.empty)

            val updatedBalance = lastAddressBalance
              .minus(toAmount(tokenLock.amount))
              .getOrElse(lastAddressBalance)

            accBalances.updated(sourceAddress, updatedBalance)
        }

        acceptedTokenUnlocks.foldLeft(balancesAfterTokenLocks) {
          case (accBalances, tokenUnlock) =>
            val sourceAddress = tokenUnlock.address
            val lastAddressBalance = accBalances.getOrElse(sourceAddress, Balance.empty)

            val updatedBalance = lastAddressBalance
              .plus(toAmount(tokenUnlock.amount))
              .getOrElse(lastAddressBalance)

            accBalances.updated(sourceAddress, updatedBalance)
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
