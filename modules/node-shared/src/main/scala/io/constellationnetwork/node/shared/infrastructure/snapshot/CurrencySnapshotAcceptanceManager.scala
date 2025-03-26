package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.{NonEmptyList, Validated}
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, Order}

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSyncView}
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
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.SelfAwareStructuredLogger
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
  stateProof: CurrencySnapshotStateProof,
  globalSyncView: GlobalSyncView
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
    lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    lastGlobalSyncView: Option[GlobalSyncView]
  )(implicit hasher: Hasher[F]): F[CurrencySnapshotAcceptanceResult]
}

object CurrencySnapshotAcceptanceManager {
  def make[F[_]: Async](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    blockAcceptanceManager: BlockAcceptanceManager[F],
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    collateral: Amount,
    messageValidator: CurrencyMessageValidator[F],
    feeTransactionValidator: FeeTransactionValidator[F],
    globalSnapshotSyncValidator: GlobalSnapshotSyncValidator[F]
  ): CurrencySnapshotAcceptanceManager[F] = new CurrencySnapshotAcceptanceManager[F] {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

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
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      lastGlobalSyncView: Option[GlobalSyncView]
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

      maybeSnapshotOrdinalSync = globalSnapshotSyncAcceptanceResult.contextUpdate.values
        .map(_.globalSnapshotOrdinal)
        .groupBy(identity)
        .maxByOption { case (ordinal, occurrences) => (occurrences.size, -ordinal.value.value) }
        .flatMap { case (ordinal, _) => SnapshotOrdinal(ordinal.value - lastGlobalSnapshotsSyncConfig.syncOffset) }

      maybeLastGlobalSnapshot <- lastGlobalSnapshots.flatMap(_.find { snapshot =>
        maybeSnapshotOrdinalSync.exists(_ === snapshot.ordinal)
      }) match {
        case some @ Some(_) => some.pure
        case None =>
          maybeSnapshotOrdinalSync match {
            case Some(ordinal) =>
              getGlobalSnapshotByOrdinal(ordinal)
            case None => none.pure
          }
      }

      lastGlobalSnapshotEpochProgress <-
        if (maybeLastGlobalSnapshot.isEmpty)
          logger
            .warn("Could not find lastGlobalSnapshot")
            .as(
              EpochProgress.MinValue
            )
        else
          maybeLastGlobalSnapshot.get.epochProgress.pure

      lastGlobalSnapshotsSpendActions <- getLastSpendActions(
        lastGlobalSyncView,
        maybeLastGlobalSnapshot,
        lastGlobalSnapshots,
        getGlobalSnapshotByOrdinal,
        metagraphId
      )

      metagraphIdSpendTransactions = lastGlobalSnapshotsSpendActions.flatMap {
        case (_, spendActions) =>
          spendActions
            .flatMap(_.spendTransactions.toList)
            .filter(_.currencyId.exists(_.value == metagraphId))
      }.toList

      _ <- metagraphIdSpendTransactions.nonEmpty
        .pure[F]
        .ifM(
          Slf4jLogger.getLogger[F].debug(s"--- [CURRENCY] Currency $metagraphId spend transactions: $metagraphIdSpendTransactions"),
          Applicative[F].unit
        )

      incomingTokenLocks = acceptanceTokenLockBlocksResult.accepted.flatMap { tokenLockBlock =>
        tokenLockBlock.value.tokenLocks.toSortedSet
      }.toSortedSet

      activeTokenLocks = lastSnapshotContext.snapshotInfo.activeTokenLocks.getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])

      tokenLocksRefs <-
        (incomingTokenLocks.toList ++ activeTokenLocks.values.flatten)
          .traverse(_.toHashed.map(_.hash))

      tokenUnlocks = acceptedSharedArtifacts.collect {
        case tokenUnlock: TokenUnlock => tokenUnlock
      }

      expiredTokenLocksHashes <-
        (incomingTokenLocks.toList ++ activeTokenLocks.values.flatten)
          .filter(_.unlockEpoch.exists(_ < lastGlobalSnapshotEpochProgress))
          .traverse(_.toHashed)
          .map(_.map(_.hash))

      acceptedTokenUnlocks = acceptTokenUnlocks(
        expiredTokenLocksHashes,
        tokenUnlocks,
        tokenLocksRefs
      )

      acceptedTokenLocks = incomingTokenLocks
        .filter(itl => itl.unlockEpoch.forall(_ >= lastGlobalSnapshotEpochProgress))
        .groupBy(_.source)
        .toSortedMap

      (updatedActiveTokenLocks, expiredTokenLocks) <- acceptTokenLocks(
        lastGlobalSnapshotEpochProgress,
        acceptedTokenLocks,
        activeTokenLocks,
        acceptedTokenUnlocks
      )

      updatedBalancesByTokenLocks = updateBalancesByTokenLocks(
        lastGlobalSnapshotEpochProgress,
        updatedBalancesByFeeTransactions,
        acceptedTokenLocks,
        activeTokenLocks,
        acceptedTokenUnlocks
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

      updatedAllowSpends <-
        acceptCurrencyAllowSpends(
          lastGlobalSnapshotEpochProgress,
          incomingCurrencyAllowSpends,
          lastActiveAllowSpends,
          metagraphIdSpendTransactions
        )

      updatedAllowSpendRefs = acceptAllowSpendRefs(
        lastAllowSpendsRefs,
        allowSpendBlockAcceptanceResult.contextUpdate.lastTxRefs
      )

      updatedBalancesByAllowSpends = updateCurrencyBalancesByAllowSpends(
        lastGlobalSnapshotEpochProgress,
        updatedBalancesByTokenLocks,
        incomingCurrencyAllowSpends,
        lastActiveAllowSpends
      )

      allActiveCurrencyAllowSpends <- (incomingCurrencyAllowSpends |+| lastActiveAllowSpends).toList.traverse {
        case (address, allowSpends) =>
          allowSpends.toList.traverse(_.toHashed).map(hashedAllowSpends => address -> hashedAllowSpends)
      }.map(_.toSortedMap)

      updatedBalancesBySpendTransactions = updateCurrencyBalancesBySpendTransactions(
        updatedBalancesByAllowSpends,
        allActiveCurrencyAllowSpends,
        metagraphIdSpendTransactions
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
        if (updatedActiveTokenLocks.nonEmpty) Some(updatedActiveTokenLocks) else None
      )

      stateProof <- csi.stateProof(snapshotOrdinal)

      allowSpendsExpiredEvents <- emitAllowSpendsExpired(
        filterExpiredAllowSpends(lastActiveAllowSpends, lastGlobalSnapshotEpochProgress)
      )

      tokenUnlocksEvents <- emitTokenUnlocks(
        acceptedTokenUnlocks,
        expiredTokenLocks
      )

      globalSyncView = maybeLastGlobalSnapshot match {
        case Some(value) => GlobalSyncView(value.ordinal, value.hash, value.epochProgress)
        case _           => GlobalSyncView.empty
      }
    } yield
      CurrencySnapshotAcceptanceResult(
        acceptanceBlocksResult,
        acceptanceTokenLockBlocksResult,
        allowSpendBlockAcceptanceResult,
        messagesAcceptanceResult,
        globalSnapshotSyncAcceptanceResult,
        acceptedRewardTxs,
        acceptedSharedArtifacts ++ allowSpendsExpiredEvents ++ tokenUnlocksEvents,
        acceptedFeeTxs,
        csi,
        stateProof,
        globalSyncView
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

    private def getLastSpendActions(
      lastGlobalSyncView: Option[GlobalSyncView],
      maybeLastGlobalSnapshot: Option[Hashed[GlobalIncrementalSnapshot]],
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      currencyId: Address
    ): F[SortedMap[Address, List[SpendAction]]] =
      maybeLastGlobalSnapshot match {
        case None => SortedMap.empty[Address, List[SpendAction]].pure[F]
        case Some(lastGlobalSnapshot) =>
          val lastSyncOrdinal = lastGlobalSyncView.map(_.ordinal).getOrElse(lastGlobalSnapshot.ordinal)

          if (lastGlobalSnapshot.ordinal.value.value < lastSyncOrdinal.value.value) {
            SortedMap.empty[Address, List[SpendAction]].pure[F]
          } else {
            val snapshotsMap = lastGlobalSnapshots.map { snapshots =>
              snapshots.map(s => (s.ordinal, s)).toMap
            }.getOrElse(Map.empty)

            val start = lastSyncOrdinal.value.value
            val end = lastGlobalSnapshot.ordinal.value.value
            val snapshotOrdinals = (start until end)
              .map(l => SnapshotOrdinal(NonNegLong.unsafeFrom(l)))
              .toList

            val limitOfOrdinalsToFetch = 20L
            if (snapshotOrdinals.length > limitOfOrdinalsToFetch) {
              Slf4jLogger
                .getLogger[F]
                .warn(
                  s"Interval of ordinals of metagraph $currencyId between lastSyncGlobalSnapshot and lastGlobalView greater than $limitOfOrdinalsToFetch, skipping fetching interval"
                )
                .as(
                  lastGlobalSnapshot.spendActions.getOrElse(SortedMap.empty[Address, List[SpendAction]])
                )
            } else {
              snapshotOrdinals.foldMapM { ordinal =>
                snapshotsMap.get(ordinal) match {
                  case Some(snapshot) =>
                    snapshot.spendActions.getOrElse(SortedMap.empty[Address, List[SpendAction]]).pure[F]
                  case None =>
                    getGlobalSnapshotByOrdinal(ordinal).map(
                      _.flatMap(_.spendActions)
                        .getOrElse(SortedMap.empty[Address, List[SpendAction]])
                    )
                }
              }
            }
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

    private def acceptTokenUnlocks(
      expiredTokenLockHashes: List[Hash],
      incomingTokenUnlocks: SortedSet[TokenUnlock],
      activeTokenLocksRefs: List[Hash]
    ): SortedSet[TokenUnlock] =
      incomingTokenUnlocks.filter { itu =>
        activeTokenLocksRefs.contains(itu.tokenLockRef) &&
        !expiredTokenLockHashes.contains(itu.tokenLockRef)
      }

    private def acceptTokenLocks(
      epochProgress: EpochProgress,
      acceptedTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      acceptedTokenUnlocks: SortedSet[TokenUnlock]
    )(implicit hasher: Hasher[F]): F[
      (
        SortedMap[Address, SortedSet[Signed[TokenLock]]],
        SortedMap[Address, SortedSet[Signed[TokenLock]]]
      )
    ] = {
      val expiredTokenLocks = filterExpiredTokenLocks(lastActiveTokenLocks, epochProgress)

      (acceptedTokenLocks |+| expiredTokenLocks).toList
        .foldM(lastActiveTokenLocks) {
          case (acc, (address, tokenLocks)) =>
            val lastAddressTokenLocks = acc.getOrElse(address, SortedSet.empty[Signed[TokenLock]])
            val unexpired = (lastAddressTokenLocks ++ tokenLocks).filter(_.unlockEpoch.forall(_ >= epochProgress))
            val unlocksRefs = acceptedTokenUnlocks.map(_.tokenLockRef)

            unexpired
              .foldM(SortedSet.empty[Signed[TokenLock]]) { (acc, tokenLock) =>
                tokenLock.toHashed.map { tlh =>
                  if (unlocksRefs.contains(tlh.hash)) acc
                  else acc + tokenLock
                }
              }
              .map { updatedLocks =>
                acc.updated(address, updatedLocks)
              }
        }
        .map(updateTokenLocks => (updateTokenLocks, expiredTokenLocks))
    }

    private def updateBalancesByTokenLocks(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      acceptedTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      acceptedTokenUnlocks: SortedSet[TokenUnlock]
    ): SortedMap[Address, Balance] = {
      val expiredGlobalTokenLocks = filterExpiredTokenLocks(lastActiveTokenLocks, epochProgress)

      (acceptedTokenLocks |+| expiredGlobalTokenLocks).foldLeft(currentBalances) {
        case (acc, (address, tokenLocks)) =>
          val unexpired = tokenLocks.filter(_.unlockEpoch.forall(_ >= epochProgress))
          val expired = tokenLocks.filter(_.unlockEpoch.exists(_ < epochProgress))

          val updatedBalanceUnexpired =
            unexpired.foldLeft(acc.getOrElse(address, Balance.empty)) { (currentBalance, tokenLock) =>
              currentBalance
                .minus(TokenLockAmount.toAmount(tokenLock.amount))
                .getOrElse(currentBalance)
                .minus(TokenLockFee.toAmount(tokenLock.fee))
                .getOrElse(currentBalance)
            }

          val updatedBalanceExpired = expired.foldLeft(updatedBalanceUnexpired) { (currentBalance, allowSpend) =>
            currentBalance
              .plus(TokenLockAmount.toAmount(allowSpend.amount))
              .getOrElse(currentBalance)
          }

          val updatedBalanceTokenUnlock = acceptedTokenUnlocks.foldLeft(updatedBalanceExpired) {
            case (accBalances, tokenUnlock) =>
              accBalances
                .plus(TokenLockAmount.toAmount(tokenUnlock.amount))
                .getOrElse(accBalances)
          }

          acc.updated(address, updatedBalanceTokenUnlock)
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

    private def filterExpiredAllowSpends(
      allowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      epochProgress: EpochProgress
    ): SortedMap[Address, SortedSet[Signed[AllowSpend]]] =
      allowSpends.view.mapValues(_.filter(_.lastValidEpochProgress < epochProgress)).to(SortedMap)

    private def filterExpiredTokenLocks(
      tokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      epochProgress: EpochProgress
    ): SortedMap[Address, SortedSet[Signed[TokenLock]]] =
      tokenLocks.view.mapValues(_.filter(_.unlockEpoch.exists(_ < epochProgress))).to(SortedMap)

    private def acceptCurrencyAllowSpends(
      epochProgress: EpochProgress,
      incomingCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      existentCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      allAcceptedSpendTxns: List[SpendTransaction]
    )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[Signed[AllowSpend]]]] = {
      val allAcceptedSpendTxnsAllowSpendsRefs =
        allAcceptedSpendTxns
          .flatMap(_.allowSpendRef)

      val expiredAllowSpends = filterExpiredAllowSpends(existentCurrencyAllowSpends, epochProgress)
      val unexpiredAllowSpends = (incomingCurrencyAllowSpends |+| expiredAllowSpends).foldLeft(existentCurrencyAllowSpends) {
        case (acc, (address, allowSpends)) =>
          val lastAddressAllowSpends = acc.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])
          val unexpired = (lastAddressAllowSpends ++ allowSpends).filter(_.lastValidEpochProgress >= epochProgress)
          acc + (address -> unexpired)
      }

      unexpiredAllowSpends.toList.foldLeftM(unexpiredAllowSpends) {
        case (acc, (address, allowSpends)) =>
          allowSpends.toList.traverse(_.toHashed).map { hashedAllowSpends =>
            val validAllowSpends = hashedAllowSpends
              .filterNot(h => allAcceptedSpendTxnsAllowSpendsRefs.contains(h.hash))
              .map(_.signed)
              .to(SortedSet)

            acc + (address -> validAllowSpends)
          }
      }
    }

    private def acceptAllowSpendRefs(
      lastAllowSpendRefs: SortedMap[Address, AllowSpendReference],
      lastAllowSpendContextUpdate: Map[Address, AllowSpendReference]
    ): SortedMap[Address, AllowSpendReference] =
      lastAllowSpendRefs ++ lastAllowSpendContextUpdate

    private def updateCurrencyBalancesByAllowSpends(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      incomingCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      lastActiveCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]]
    ): SortedMap[Address, Balance] = {
      val expiredCurrencyAllowSpends = filterExpiredAllowSpends(lastActiveCurrencyAllowSpends, epochProgress)

      (incomingCurrencyAllowSpends |+| expiredCurrencyAllowSpends).foldLeft(currentBalances) {
        case (acc, (address, allowSpends)) =>
          val unexpired = allowSpends.filter(_.lastValidEpochProgress >= epochProgress)
          val expired = allowSpends.filter(_.lastValidEpochProgress < epochProgress)

          val updatedBalanceUnexpired =
            unexpired.foldLeft(acc.getOrElse(address, Balance.empty)) { (currentBalance, allowSpend) =>
              currentBalance
                .minus(SwapAmount.toAmount(allowSpend.amount))
                .getOrElse(currentBalance)
                .minus(AllowSpendFee.toAmount(allowSpend.fee))
                .getOrElse(currentBalance)
            }
          val updatedBalanceExpired = expired.foldLeft(updatedBalanceUnexpired) { (currentBalance, allowSpend) =>
            currentBalance
              .plus(SwapAmount.toAmount(allowSpend.amount))
              .getOrElse(currentBalance)
          }

          acc.updated(address, updatedBalanceExpired)
      }
    }

    private def updateCurrencyBalancesBySpendTransactions(
      currentBalances: SortedMap[Address, Balance],
      allActiveCurrencyAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]],
      metagraphIdSpendTransactions: List[SpendTransaction]
    ): SortedMap[Address, Balance] =
      metagraphIdSpendTransactions.foldLeft(currentBalances) { (txnAcc, spendTransaction) =>
        val destinationAddress = spendTransaction.destination
        val sourceAddress = spendTransaction.source

        val addressAllowSpends = allActiveCurrencyAllowSpends.getOrElse(sourceAddress, List.empty)
        val spendTransactionAmount = SwapAmount.toAmount(spendTransaction.amount)
        val currentDestinationBalance = txnAcc.getOrElse(destinationAddress, Balance.empty)

        spendTransaction.allowSpendRef.flatMap { allowSpendRef =>
          addressAllowSpends.find(_.hash === allowSpendRef)
        } match {
          case Some(allowSpend) =>
            val sourceAddress = allowSpend.source
            val currentSourceBalance = txnAcc.getOrElse(sourceAddress, Balance.empty)
            val balanceToReturnToAddress = allowSpend.amount.value.value - spendTransactionAmount.value.value

            val updatedDestinationBalance = currentDestinationBalance
              .plus(spendTransactionAmount)
              .getOrElse(currentDestinationBalance)

            val updatedSourceBalance = currentSourceBalance
              .plus(Amount(NonNegLong.from(balanceToReturnToAddress).getOrElse(NonNegLong.MinValue)))
              .getOrElse(currentSourceBalance)

            txnAcc
              .updated(destinationAddress, updatedDestinationBalance)
              .updated(sourceAddress, updatedSourceBalance)

          case None =>
            val currentSourceBalance = txnAcc.getOrElse(sourceAddress, Balance.empty)

            val updatedDestinationBalance = currentDestinationBalance
              .plus(spendTransactionAmount)
              .getOrElse(currentDestinationBalance)

            val updatedSourceBalance = currentSourceBalance
              .minus(spendTransactionAmount)
              .getOrElse(currentSourceBalance)

            txnAcc
              .updated(destinationAddress, updatedDestinationBalance)
              .updated(sourceAddress, updatedSourceBalance)
        }
      }

    def emitAllowSpendsExpired(
      addressToSet: SortedMap[Address, SortedSet[Signed[AllowSpend]]]
    )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]] =
      addressToSet.values.flatten.toList
        .traverse(_.toHashed)
        .map(_.map(hashed => AllowSpendExpiration(hashed.hash): SharedArtifact).toSortedSet)

    def emitTokenUnlocks(
      acceptedTokenUnlocks: SortedSet[TokenUnlock],
      expiredTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
    )(implicit hasher: Hasher[F]): F[SortedSet[SharedArtifact]] = {
      val acceptedTokenUnlocksHashes = acceptedTokenUnlocks.map(_.tokenLockRef)

      expiredTokenLocks.values.flatten.toList
        .traverse(_.toHashed)
        .map { hashedLocks =>
          val newUnlocks = hashedLocks.collect {
            case hashed if !acceptedTokenUnlocksHashes.contains(hashed.hash) =>
              TokenUnlock(
                hashed.hash,
                hashed.amount,
                hashed.currencyId,
                hashed.source
              )
          }

          val newUnlocksAsShared: SortedSet[SharedArtifact] =
            SortedSet.from[SharedArtifact](newUnlocks)
          val acceptedUnlocksAsShared: SortedSet[SharedArtifact] =
            SortedSet.from[SharedArtifact](acceptedTokenUnlocks)

          newUnlocksAsShared ++ acceptedUnlocksAsShared
        }
    }
  }
}
