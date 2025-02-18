package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Order
import cats.data.{NonEmptyList, Validated}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSnapshotSync
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.swap.block.{
  AllowSpendBlockAcceptanceContext,
  AllowSpendBlockAcceptanceManager,
  AllowSpendBlockAcceptanceResult
}
import io.constellationnetwork.node.shared.domain.tokenlock.block.{
  TokenLockBlockAcceptanceContext,
  TokenLockBlockAcceptanceManager,
  TokenLockBlockAcceptanceResult
}
import io.constellationnetwork.node.shared.domain.transaction.FeeTransactionValidator
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.currencyMessage._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock.TokenLockAmount.toAmount
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockBlock, TokenLockReference}
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

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
  allowSpendBlock: AllowSpendBlockAcceptanceResult,
  messages: CurrencyMessagesAcceptanceResult,
  globalSnapshotSync: GlobalSnapshotSyncAcceptanceResult,
  rewards: SortedSet[RewardTransaction],
  sharedArtifacts: SortedSet[SharedArtifact],
  feeTransactions: Option[SortedSet[Signed[FeeTransaction]]],
  info: CurrencySnapshotInfo,
  stateProof: CurrencySnapshotStateProof
)

trait CurrencySnapshotAcceptanceManager[F[_]] {
  def accept(
    blocksForAcceptance: List[Signed[Block]],
    tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
    allowSpendBlocksForAcceptance: List[Signed[AllowSpendBlock]],
    messagesForAcceptance: List[Signed[CurrencyMessage]],
    feeTransactionsForAcceptance: Option[SortedSet[Signed[FeeTransaction]]],
    globalSnapshotSyncsForAcceptance: List[Signed[GlobalSnapshotSync]],
    sharedArtifactsForAcceptance: SortedSet[SharedArtifact],
    lastSnapshotContext: CurrencySnapshotContext,
    snapshotOrdinal: SnapshotOrdinal,
    epochProgress: EpochProgress,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
    facilitators: Set[PeerId],
    lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[CurrencySnapshotAcceptanceResult]
}

object CurrencySnapshotAcceptanceManager {
  def make[F[_]: Async](
    lastGlobalSnapshotSyncCfg: LastGlobalSnapshotsSyncConfig,
    blockAcceptanceManager: BlockAcceptanceManager[F],
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    collateral: Amount,
    messageValidator: CurrencyMessageValidator[F],
    feeTransactionValidator: FeeTransactionValidator[F],
    globalSnapshotSyncValidator: GlobalSnapshotSyncValidator[F]
  ) = new CurrencySnapshotAcceptanceManager[F] {
    val logger = Slf4jLogger.getLogger[F]

    def accept(
      blocksForAcceptance: List[Signed[Block]],
      tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
      allowSpendBlocksForAcceptance: List[Signed[AllowSpendBlock]],
      messagesForAcceptance: List[Signed[CurrencyMessage]],
      feeTransactionsForAcceptance: Option[SortedSet[Signed[FeeTransaction]]],
      globalSnapshotSyncsForAcceptance: List[Signed[GlobalSnapshotSync]],
      sharedArtifactsForAcceptance: SortedSet[SharedArtifact],
      lastSnapshotContext: CurrencySnapshotContext,
      snapshotOrdinal: SnapshotOrdinal,
      epochProgress: EpochProgress,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
      facilitators: Set[PeerId],
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
    )(implicit hasher: Hasher[F]): F[CurrencySnapshotAcceptanceResult] = for {
      initialTxRef <- TransactionReference.emptyCurrency(lastSnapshotContext.address)
      tokenLockInitialTxRef <- TokenLockReference.emptyCurrency(lastSnapshotContext.address)
      initialAllowSpendRef <- AllowSpendReference.emptyCurrency(lastSnapshotContext.address)
      metagraphId = lastSnapshotContext.address

      acceptanceBlocksResult <- acceptBlocks(
        blocksForAcceptance,
        lastSnapshotContext,
        snapshotOrdinal,
        lastActiveTips,
        lastDeprecatedTips,
        initialTxRef
      )

      acceptedTransactions = acceptanceBlocksResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

      acceptanceTokenLockBlocksResult <- acceptTokenLockBlocks(
        tokenLockBlocksForAcceptance,
        lastSnapshotContext,
        snapshotOrdinal,
        tokenLockInitialTxRef
      )

      allowSpendBlockAcceptanceResult <- acceptAllowSpendBlocks(
        allowSpendBlocksForAcceptance,
        lastSnapshotContext,
        snapshotOrdinal,
        initialAllowSpendRef
      )

      transactionsRefs = acceptTransactionRefs(
        lastSnapshotContext.snapshotInfo.lastTxRefs,
        acceptanceBlocksResult.contextUpdate.lastTxRefs,
        acceptedTransactions
      )

      tokenLockRefs = acceptTokenLockRefs(
        lastSnapshotContext.snapshotInfo.lastTokenLockRefs.getOrElse(SortedMap.empty[Address, TokenLockReference]),
        acceptanceTokenLockBlocksResult.contextUpdate.lastTokenLocksRefs
      )

      rewards <- calculateRewardsFn(acceptedTransactions)

      (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
        lastSnapshotContext.snapshotInfo.balances ++ acceptanceBlocksResult.contextUpdate.balances,
        rewards
      )

      _ <- validateFeeTxs(feeTransactionsForAcceptance)

      (updatedBalancesByFeeTransactions, acceptedFeeTxs) <- acceptFeeTxs(
        updatedBalancesByRewards,
        feeTransactionsForAcceptance
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

      maybeSnapshotOrdinalSync = lastSnapshotContext.snapshotInfo.globalSnapshotSyncView.traverse {
        _.values
          .map(_.globalSnapshotOrdinal)
          .groupBy(identity)
          .maxByOption { case (ordinal, occurrences) => (occurrences.size, -ordinal.value.value) }
          .flatMap { case (ordinal, _) => SnapshotOrdinal(ordinal.value) }
      }.flatten

      maybeLastGlobalSnapshot = lastGlobalSnapshots
        .flatMap(_.find { snapshot =>
          maybeSnapshotOrdinalSync.exists { snapshotOrdinalSync =>
            snapshotOrdinalSync === snapshot.ordinal
          }
        })

      lastGlobalSnapshotEpochProgress <-
        if (maybeLastGlobalSnapshot.isEmpty)
          logger
            .warn("Could not find lastGlobalSnapshot")
            .as(
              EpochProgress.MinValue
            )
        else
          maybeLastGlobalSnapshot.get.epochProgress.pure

      lastGlobalSnapshotSpendActions = maybeLastGlobalSnapshot
        .flatMap(_.spendActions)
        .getOrElse(SortedMap.empty[Address, List[SpendAction]])

      lastMetagraphSpendActions = lastGlobalSnapshotSpendActions.getOrElse(metagraphId, List.empty)

      allAcceptedSpendTxns =
        lastMetagraphSpendActions
          .flatMap(spendTxn => List(spendTxn.input, spendTxn.output))

      allAcceptedSpendTxnsAllowSpendsRefs =
        allAcceptedSpendTxns
          .flatMap(_.allowSpendRef)

      incomingTokenLocks = acceptanceTokenLockBlocksResult.accepted.flatMap { tokenLockBlock =>
        tokenLockBlock.value.tokenLocks.toSortedSet
      }.toSortedSet

      activeTokenLocks = lastSnapshotContext.snapshotInfo.activeTokenLocks

      tokenLocksRefs <-
        (incomingTokenLocks.toList ++ activeTokenLocks.toList.flatMap(_.values).flatten)
          .traverse(_.toHashed.map(_.hash))

      tokenUnlocks = acceptedSharedArtifacts.collect {
        case tokenUnlock: TokenUnlock => tokenUnlock
      }

      acceptedTokenLocks = acceptTokenLocks(
        incomingTokenLocks,
        lastGlobalSnapshotEpochProgress
      )

      acceptedTokenUnlocks = acceptTokenUnlocks(
        tokenUnlocks,
        tokenLocksRefs
      )

      updatedActiveTokenLocks <- updateActiveTokenLocks(
        activeTokenLocks,
        acceptedTokenLocks,
        acceptedTokenUnlocks
      )

      updatedBalancesByTokenLocks = updateBalancesByTokenLocks(
        acceptedTokenLocks,
        acceptedTokenUnlocks,
        updatedBalancesByFeeTransactions
      )

      acceptedCurrencyAllowSpends = allowSpendBlockAcceptanceResult.accepted.flatMap(_.value.transactions.toList)
      incomingCurrencyAllowSpends = acceptedCurrencyAllowSpends
        .groupBy(_.value.source)
        .view
        .mapValues(SortedSet.from(_))
        .to(SortedMap)

      lastActiveAllowSpends = lastSnapshotContext.snapshotInfo.activeAllowSpends.getOrElse(
        SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]]
      )
      lastAllowSpendsRefs = lastSnapshotContext.snapshotInfo.lastAllowSpendRefs.getOrElse(SortedMap.empty[Address, AllowSpendReference])

      (updatedAllowSpends, expiredAllowSpends, allowSpendsWithSpendTransaction) <-
        acceptAllowSpends(
          lastGlobalSnapshotEpochProgress,
          incomingCurrencyAllowSpends,
          lastActiveAllowSpends,
          allAcceptedSpendTxnsAllowSpendsRefs
        )

      updatedAllowSpendRefs = acceptAllowSpendRefs(
        lastAllowSpendsRefs,
        allowSpendBlockAcceptanceResult.contextUpdate.lastTxRefs
      )

      updatedBalancesByAllowSpends = updateGlobalBalancesByAllowSpends(
        updatedBalancesByTokenLocks,
        incomingCurrencyAllowSpends,
        expiredAllowSpends
      )

      updatedBalancesBySpendTransactions <- updateGlobalBalancesBySpendTransactions(
        updatedBalancesByAllowSpends,
        allowSpendsWithSpendTransaction,
        allAcceptedSpendTxns
      )

      csi = CurrencySnapshotInfo(
        transactionsRefs,
        updatedBalancesBySpendTransactions,
        Option.when(messagesAcceptanceResult.contextUpdate.nonEmpty)(messagesAcceptanceResult.contextUpdate),
        None,
        updatedAllowSpendRefs.some,
        updatedAllowSpends.some,
        globalSnapshotSyncAcceptanceResult.contextUpdate.some,
        tokenLockRefs.some,
        updatedActiveTokenLocks
      )

      stateProof <- csi.stateProof(snapshotOrdinal)

      allowSpendsExpiredEvents <- emitAllowSpendsExpired(
        expiredAllowSpends
      )
    } yield
      CurrencySnapshotAcceptanceResult(
        acceptanceBlocksResult,
        acceptanceTokenLockBlocksResult,
        allowSpendBlockAcceptanceResult,
        messagesAcceptanceResult,
        globalSnapshotSyncAcceptanceResult,
        acceptedRewardTxs,
        acceptedSharedArtifacts ++ allowSpendsExpiredEvents,
        acceptedFeeTxs,
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
          Order.by(_.parentOrdinal),
          Order[Signed[GlobalSnapshotSync]]
        )
        .toOrdering

      globalSnapshotSyncsForAcceptance
        .sorted(ordering)
        .foldLeftM(
          (
            lastGlobalSnapshotSyncView.getOrElse(SortedMap.empty[PeerId, Signed[GlobalSnapshotSync]]),
            List.empty[Signed[GlobalSnapshotSync]],
            List.empty[Signed[GlobalSnapshotSync]]
          )
        ) {
          case ((lastSyncs, toAdd, toReject), sync) =>
            globalSnapshotSyncValidator.validate(sync, metagraphId, facilitators, lastSyncs).map {
              case Validated.Valid(_) =>
                val peerId = sync.proofs.head.id.toPeerId
                val updatedLastSyncs = lastSyncs.updated(peerId, sync)
                val updatedToAdd = sync :: toAdd

                (updatedLastSyncs, updatedToAdd, toReject)
              case Validated.Invalid(_) =>
                val updatedToReject = sync :: toReject

                (lastSyncs, toAdd, updatedToReject)
            }
        }
        .map { case (contextUpdate, toAdd, toReject) => GlobalSnapshotSyncAcceptanceResult(contextUpdate, toAdd, toReject) }
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
        lastSnapshotContext.snapshotInfo.lastTokenLockRefs.getOrElse(SortedMap.empty),
        collateral,
        initialTxRef
      )

      tokenLockBlockAcceptanceManager.acceptBlocksIteratively(tokenLockBlocksForAcceptance, context, snapshotOrdinal)
    }

    private def acceptAllowSpendBlocks(
      blocksForAcceptance: List[Signed[AllowSpendBlock]],
      lastSnapshotContext: CurrencySnapshotContext,
      snapshotOrdinal: SnapshotOrdinal,
      initialTxRef: AllowSpendReference
    )(implicit hasher: Hasher[F]) = {
      val context = AllowSpendBlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.snapshotInfo.balances,
        lastSnapshotContext.snapshotInfo.lastAllowSpendRefs.getOrElse(Map.empty),
        collateral,
        initialTxRef
      )

      allowSpendBlockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context, snapshotOrdinal)
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

    private def validateFeeTxs(
      maybeTxs: Option[SortedSet[Signed[FeeTransaction]]]
    ): F[Unit] =
      NonEmptyList.fromList(maybeTxs.toList.flatMap(_.toList)).fold(().pure[F]) { nonEmptyTxs =>
        feeTransactionValidator.validate(nonEmptyTxs).flatMap {
          case Validated.Valid(_) =>
            ().pure[F]
          case Validated.Invalid(errors) =>
            new Exception(s"FeeTransaction validation failed: ${errors.toList.mkString(", ")}")
              .raiseError[F, Unit]
        }
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
      activeTokenLocksRefs: List[Hash]
    ): SortedSet[TokenUnlock] =
      incomingTokenUnlocks.filter { itu =>
        activeTokenLocksRefs.contains(itu.tokenLockRef)
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
                signedTokenLock.toHashed.map { hashedTokenLock =>
                  acceptedTokenUnlocks.forall(_.tokenLockRef =!= hashedTokenLock.hash)
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

    private def filterAllowsSpends(
      allowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      allAcceptedSpendTxnsAllowSpendsRefs: List[Hash],
      spendTransactionAssociated: Boolean
    )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[Signed[AllowSpend]]]] =
      allowSpends.toList.traverse {
        case (address, allowSpends) =>
          allowSpends.toList.traverse(_.toHashed).map { hashedAllowSpends =>
            val validAllowSpends = if (spendTransactionAssociated) {
              hashedAllowSpends.filter(h => allAcceptedSpendTxnsAllowSpendsRefs.contains(h.hash))
            } else {
              hashedAllowSpends.filterNot(h => allAcceptedSpendTxnsAllowSpendsRefs.contains(h.hash))
            }
            address -> validAllowSpends.map(_.signed).toSortedSet
          }
      }
        .map(_.toSortedMap)

    private def acceptAllowSpends(
      epochProgress: EpochProgress,
      incomingCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      existentCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      allAcceptedSpendTxnsAllowSpendsRefs: List[Hash]
    )(implicit hasher: Hasher[F]): F[
      (
        SortedMap[Address, SortedSet[Signed[AllowSpend]]],
        SortedMap[Address, SortedSet[Signed[AllowSpend]]],
        SortedMap[Address, SortedSet[Signed[AllowSpend]]]
      )
    ] = {
      val expiredAllowSpends = (incomingCurrencyAllowSpends.toList ++ existentCurrencyAllowSpends.toList).groupBy {
        case (address, _) => address
      }.view
        .mapValues(_.flatMap { case (_, allowSpends) => allowSpends }.toSortedSet)
        .collect {
          case (address, allowSpends) => address -> allowSpends.filter(_.lastValidEpochProgress < epochProgress)
        }
        .toSortedMap

      val unexpiredAllowSpends =
        (incomingCurrencyAllowSpends.toList ++ expiredAllowSpends.toList).groupBy { case (address, _) => address }.view
          .mapValues(_.flatMap { case (_, allowSpends) => allowSpends }.toSortedSet)
          .foldLeft(existentCurrencyAllowSpends) {
            case (acc, (address, allowSpends)) =>
              val lastAddressAllowSpends = acc.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])
              val unexpired = (lastAddressAllowSpends ++ allowSpends).filter(_.lastValidEpochProgress >= epochProgress)
              acc + (address -> unexpired)
          }

      for {
        allowSpendsWithSpendTransaction <- filterAllowsSpends(
          unexpiredAllowSpends,
          allAcceptedSpendTxnsAllowSpendsRefs,
          spendTransactionAssociated = true
        )
        unexpiredWithoutSpendTransactions =
          unexpiredAllowSpends.map {
            case (address, allowSpends) =>
              val withoutSpendTransactions =
                allowSpends.diff(allowSpendsWithSpendTransaction.getOrElse(address, SortedSet.empty))
              address -> withoutSpendTransactions
          }.filter { case (_, allowSpends) => allowSpends.nonEmpty }
      } yield
        (
          unexpiredWithoutSpendTransactions,
          expiredAllowSpends,
          allowSpendsWithSpendTransaction
        )
    }

    private def acceptAllowSpendRefs(
      lastAllowSpendRefs: SortedMap[Address, AllowSpendReference],
      lastAllowSpendContextUpdate: Map[Address, AllowSpendReference]
    ): SortedMap[Address, AllowSpendReference] =
      lastAllowSpendRefs ++ lastAllowSpendContextUpdate

    private def updateGlobalBalancesByAllowSpends(
      currentBalances: SortedMap[Address, Balance],
      incomingCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      expiredAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]]
    ): SortedMap[Address, Balance] = {
      val allowSpendsToLockBalances: SortedMap[Address, SortedSet[Signed[AllowSpend]]] =
        incomingCurrencyAllowSpends.map {
          case (address, spends) =>
            val filteredSpends = spends
              .diff(expiredAllowSpends.getOrElse(address, SortedSet.empty))
            address -> filteredSpends
        }.filter { case (_, spends) => spends.nonEmpty }

      val balancesUpdatedByNewAllowSpends = allowSpendsToLockBalances.foldLeft(currentBalances) {
        case (acc, (address, allowSpends)) =>
          val updatedBalance = allowSpends.foldLeft(acc.getOrElse(address, Balance.empty)) { (balance, allowSpend) =>
            balance
              .minus(SwapAmount.toAmount(allowSpend.amount))
              .getOrElse(balance)
              .minus(AllowSpendFee.toAmount(allowSpend.fee))
              .getOrElse(balance)
          }
          acc.updated(address, updatedBalance)
      }

      expiredAllowSpends.foldLeft(balancesUpdatedByNewAllowSpends) {
        case (acc, (address, allowSpends)) =>
          val updatedBalance = allowSpends.foldLeft(acc.getOrElse(address, Balance.empty)) { (balance, allowSpend) =>
            balance.plus(SwapAmount.toAmount(allowSpend.amount)).getOrElse(balance)
          }
          acc.updated(address, updatedBalance)
      }
    }

    private def updateGlobalBalancesBySpendTransactions(
      currentBalances: SortedMap[Address, Balance],
      allowSpendsWithSpendTransactions: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      allAcceptedSpendTxns: List[SpendTransaction]
    )(implicit hasher: Hasher[F]): F[SortedMap[Address, Balance]] =
      allAcceptedSpendTxns.foldLeftM(currentBalances) { (acc, spendTransaction) =>
        val currentDestinationBalance = acc.getOrElse(spendTransaction.destination, Balance.empty)
        val updatedDestinationBalance = currentDestinationBalance
          .plus(SwapAmount.toAmount(spendTransaction.amount))
          .getOrElse(currentDestinationBalance)

        val allowSpendLookup: F[Option[Hashed[AllowSpend]]] = spendTransaction.allowSpendRef match {
          case Some(allowSpendRef) =>
            allowSpendsWithSpendTransactions.values.flatten.toList
              .traverse(_.toHashed)
              .map(_.find(_.hash === allowSpendRef))
          case None => Async[F].pure(None)
        }

        allowSpendLookup.flatMap {
          case Some(allowSpend) =>
            val diffBetweenAllowSpendAndSpendTransaction = SwapAmount
              .toAmount(allowSpend.signed.value.amount)
              .minus(SwapAmount.toAmount(spendTransaction.amount))
              .getOrElse(Amount.empty)

            val currentSourceBalance = acc.getOrElse(allowSpend.signed.value.source, Balance.empty)
            Async[F].pure(
              acc
                .updated(spendTransaction.destination, updatedDestinationBalance)
                .updated(
                  allowSpend.signed.value.source,
                  currentSourceBalance.plus(diffBetweenAllowSpendAndSpendTransaction).getOrElse(currentSourceBalance)
                )
            )

          case None =>
            Async[F].pure(acc.updated(spendTransaction.destination, updatedDestinationBalance))
        }
      }

    def emitAllowSpendsExpired(
      addressToSet: SortedMap[address.Address, SortedSet[Signed[swap.AllowSpend]]]
    )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]] =
      addressToSet.values.flatten.toList
        .traverse(_.toHashed)
        .map(_.map(hashed => AllowSpendExpiration(hashed.hash): SharedArtifact).toSortedSet)
  }

}
