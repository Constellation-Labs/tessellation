package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.{NonEmptyList, Validated}
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, Order, Parallel}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.collection.mutable

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
import io.constellationnetwork.schema.balance.{Amount, Balance, BalanceArithmeticError}
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
import fs2.concurrent.SignallingRef
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
  globalSyncView: GlobalSyncView,
  syncGlobalSnapshotOrdinal: SnapshotOrdinal,
  lastGlobalSnapshotToCheckFields: SnapshotOrdinal
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
    getLastNGlobalSnapshots: => F[List[Hashed[GlobalIncrementalSnapshot]]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    lastGlobalSyncView: Option[GlobalSyncView]
  )(implicit hasher: Hasher[F]): F[CurrencySnapshotAcceptanceResult]

  def acceptRewardTxs(
    baseBalances: SortedMap[Address, Balance],
    newUpdatedBalance: Map[Address, Balance],
    rewards: SortedSet[RewardTransaction]
  ): F[(SortedMap[Address, Balance], SortedSet[RewardTransaction])]
}

object CurrencySnapshotAcceptanceManager {
  def make[F[_]: Async: Parallel](
    tessellation3MigrationStartingOrdinal: SnapshotOrdinal,
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    blockAcceptanceManager: BlockAcceptanceManager[F],
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    collateral: Amount,
    messageValidator: CurrencyMessageValidator[F],
    feeTransactionValidator: FeeTransactionValidator[F],
    globalSnapshotSyncValidator: GlobalSnapshotSyncValidator[F]
  ): F[CurrencySnapshotAcceptanceManager[F]] =
    SignallingRef
      .of[F, Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]](SortedMap.empty)
      .map(
        make[F](
          tessellation3MigrationStartingOrdinal,
          lastGlobalSnapshotsSyncConfig,
          blockAcceptanceManager,
          tokenLockBlockAcceptanceManager,
          allowSpendBlockAcceptanceManager,
          collateral,
          messageValidator,
          feeTransactionValidator,
          globalSnapshotSyncValidator,
          _
        )
      )

  def make[F[_]: Async: Parallel](
    tessellation3MigrationStartingOrdinal: SnapshotOrdinal,
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    blockAcceptanceManager: BlockAcceptanceManager[F],
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    collateral: Amount,
    messageValidator: CurrencyMessageValidator[F],
    feeTransactionValidator: FeeTransactionValidator[F],
    globalSnapshotSyncValidator: GlobalSnapshotSyncValidator[F],
    lastGlobalSnapshotsCached: SignallingRef[F, Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]]
  ): CurrencySnapshotAcceptanceManager[F] = new CurrencySnapshotAcceptanceManager[F] {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("CurrencySnapshotAcceptanceManager")

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
      getLastNGlobalSnapshots: => F[List[Hashed[GlobalIncrementalSnapshot]]],
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

      (updatedBalancesByRewards, acceptedRewardTxs) <- acceptRewardTxs(
        lastSnapshotContext.snapshotInfo.balances,
        acceptanceBlocksResult.contextUpdate.balances,
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

      lastGlobalSnapshots <- getLastNGlobalSnapshots
      _ <- logger.info(s"Metagraph $metagraphId snapshot $snapshotOrdinal - maybeSnapshotOrdinalSync: $maybeSnapshotOrdinalSync")

      maybeLastGlobalSnapshot <- maybeSnapshotOrdinalSync match {
        case Some(ordinal) =>
          lastGlobalSnapshots.find(_.ordinal === ordinal) match {
            case some @ Some(_) =>
              some.pure[F]
            case None =>
              lastGlobalSnapshotsCached.get.flatMap { cache =>
                cache.get(ordinal) match {
                  case Some(snapshot) => snapshot.some.pure[F]
                  case None           => getGlobalSnapshotByOrdinal(ordinal)
                }
              }
          }
        case None =>
          none.pure[F]
      }

      _ <- maybeLastGlobalSnapshot match {
        case Some(snapshot) =>
          lastGlobalSnapshotsCached.update { current =>
            val updated = current.updated(snapshot.ordinal, snapshot)
            updated.toSeq
              .sortBy(_._1.value.value)
              .takeRight(lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory)
              .toMap
          }
        case _ =>
          Applicative[F].unit
      }

      lastGlobalSnapshotEpochProgress <-
        if (maybeLastGlobalSnapshot.isEmpty)
          logger
            .warn("Could not find lastGlobalSnapshot to extract epochProgress")
            .as(
              EpochProgress.MinValue
            )
        else
          maybeLastGlobalSnapshot.get.epochProgress.pure

      lastGlobalSnapshotOrdinal <-
        if (maybeLastGlobalSnapshot.isEmpty)
          logger
            .warn("Could not find lastGlobalSnapshot to extract ordinal")
            .as(
              SnapshotOrdinal.MinValue
            )
        else
          maybeLastGlobalSnapshot.get.ordinal.pure

      lastGlobalSnapshotsSpendActions <- getLastSpendActions(
        lastGlobalSyncView,
        maybeLastGlobalSnapshot,
        lastGlobalSnapshots,
        getGlobalSnapshotByOrdinal,
        metagraphId,
        snapshotOrdinal
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
          logger.info(s"--- [CURRENCY] Currency $metagraphId spend transactions: $metagraphIdSpendTransactions"),
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
      ) match {
        case Right(balances) => balances
        case Left(error)     => throw new RuntimeException(s"Balance arithmetic error updating balances by token locks: $error")
      }

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
      ) match {
        case Right(balances) => balances
        case Left(error)     => throw new RuntimeException(s"Balance arithmetic error updating balances by allow spends: $error")
      }

      allActiveCurrencyAllowSpends <- (incomingCurrencyAllowSpends |+| lastActiveAllowSpends).toList.traverse {
        case (address, allowSpends) =>
          allowSpends.toList.traverse(_.toHashed).map(hashedAllowSpends => address -> hashedAllowSpends)
      }.map(_.toSortedMap)

      updatedBalancesBySpendTransactions = updateCurrencyBalancesBySpendTransactions(
        updatedBalancesByAllowSpends,
        allActiveCurrencyAllowSpends,
        metagraphIdSpendTransactions
      ) match {
        case Right(balances) => balances
        case Left(error)     => throw new RuntimeException(s"Balance arithmetic error updating balances by spend transactions: $error")
      }
      updatedAllowSpendsCleaned = updatedAllowSpends.filter { case (_, allowSpends) => allowSpends.nonEmpty }
      updatedActiveTokenLocksCleaned = updatedActiveTokenLocks.filter { case (_, tokenLocks) => tokenLocks.nonEmpty }

      snapshotOrdinalToCheckFields =
        if (lastGlobalSnapshotOrdinal === SnapshotOrdinal.MinValue) {
          lastGlobalSnapshots.lastOption.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue)
        } else {
          lastGlobalSnapshotOrdinal
        }

      csi = CurrencySnapshotInfo(
        if (snapshotOrdinalToCheckFields < tessellation3MigrationStartingOrdinal)
          lastSnapshotContext.snapshotInfo.lastTxRefs ++ acceptanceBlocksResult.contextUpdate.lastTxRefs
        else transactionsRefs,
        updatedBalancesBySpendTransactions,
        Option.when(messagesAcceptanceResult.contextUpdate.nonEmpty)(messagesAcceptanceResult.contextUpdate),
        None,
        if (snapshotOrdinalToCheckFields < tessellation3MigrationStartingOrdinal) none else updatedAllowSpendRefs.some,
        if (snapshotOrdinalToCheckFields < tessellation3MigrationStartingOrdinal) none else updatedAllowSpendsCleaned.some,
        if (snapshotOrdinalToCheckFields < tessellation3MigrationStartingOrdinal) none
        else globalSnapshotSyncAcceptanceResult.contextUpdate.some,
        if (snapshotOrdinalToCheckFields < tessellation3MigrationStartingOrdinal) none else tokenLockRefs.some,
        if (snapshotOrdinalToCheckFields < tessellation3MigrationStartingOrdinal) none else updatedActiveTokenLocksCleaned.some
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

      _ <- logger.info(s"Metagraph $metagraphId snapshot $snapshotOrdinal - globalSyncView: $globalSyncView")
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
        globalSyncView,
        lastGlobalSnapshotOrdinal,
        snapshotOrdinalToCheckFields
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
      lastGlobalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      currencyId: Address,
      snapshotOrdinal: SnapshotOrdinal
    ): F[SortedMap[Address, List[SpendAction]]] = {
      val empty = SortedMap.empty[Address, List[SpendAction]].pure[F]
      maybeLastGlobalSnapshot match {
        case None => empty
        case Some(lastGlobalSnapshot) =>
          val lastSyncOrdinal = lastGlobalSyncView.map(_.ordinal).getOrElse(lastGlobalSnapshot.ordinal)
          if (lastGlobalSnapshot.ordinal.value.value < lastSyncOrdinal.value.value) {
            empty
          } else {
            val snapshotCache: Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]] =
              lastGlobalSnapshots.map(s => s.ordinal -> s).toMap

            val startOrdinal = lastSyncOrdinal.value.value
            val endOrdinal = lastGlobalSnapshot.ordinal.value.value
            val snapshotOrdinals: List[SnapshotOrdinal] =
              (startOrdinal until endOrdinal).map(i => SnapshotOrdinal(NonNegLong.unsafeFrom(i))).toList

            if (snapshotOrdinals.size > lastGlobalSnapshotsSyncConfig.maxAllowedGap.value) {
              logger
                .warn(
                  s"Interval of ordinals of metagraph $currencyId ordinal: $snapshotOrdinal between lastSyncGlobalSnapshot: $startOrdinal and lastGlobalView: $endOrdinal is greater than ${lastGlobalSnapshotsSyncConfig.maxAllowedGap}; skipping fetching interval"
                )
                .as(lastGlobalSnapshot.spendActions.getOrElse(SortedMap.empty))
            } else {
              val (cached, missing) = snapshotOrdinals.partition(snapshotCache.contains)

              val fromCache: List[SortedMap[Address, List[SpendAction]]] =
                cached.flatMap(ordinal => snapshotCache.get(ordinal).flatMap(_.spendActions).toList)

              val fetchMissing: F[List[SortedMap[Address, List[SpendAction]]]] =
                missing.parTraverse { ordinal =>
                  getGlobalSnapshotByOrdinal(ordinal)
                    .map(_.flatMap(_.spendActions).getOrElse(SortedMap.empty))
                }

              for {
                fromFetched <- fetchMissing
              } yield (fromCache ++ fromFetched).reduceOption(_ ++ _).getOrElse(SortedMap.empty)
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

    def acceptRewardTxs(
      baseBalances: SortedMap[Address, Balance],
      newUpdatedBalance: Map[Address, Balance],
      rewards: SortedSet[RewardTransaction]
    ): F[(SortedMap[Address, Balance], SortedSet[RewardTransaction])] = {
      val mutableBalances = mutable.Map.from(baseBalances)
      newUpdatedBalance.foreach {
        case (addr, delta) =>
          mutableBalances.update(addr, delta)
      }

      val acceptedRewards = mutable.Set.empty[RewardTransaction]
      rewards.foreach { tx =>
        val current = mutableBalances.getOrElse(tx.destination, Balance.empty)
        current.plus(tx.amount) match {
          case Right(newBal) =>
            mutableBalances.update(tx.destination, newBal)
            acceptedRewards += tx
          case Left(error) =>
            logger
              .warn(error)(s"Invalid balance update. Current Balance: $current}, RewardTransaction: $tx}")
              .as(())
        }
      }

      val finalBalances = SortedMap.from(mutableBalances)
      val acceptedTxs = SortedSet.from(acceptedRewards)

      (finalBalances, acceptedTxs).pure
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
    ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] = {
      val expiredGlobalTokenLocks = filterExpiredTokenLocks(lastActiveTokenLocks, epochProgress)

      (acceptedTokenLocks |+| expiredGlobalTokenLocks).foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](
        Right(currentBalances)
      ) {
        case (accEither, (address, tokenLocks)) =>
          for {
            acc <- accEither
            initialBalance = acc.getOrElse(address, Balance.empty)

            unexpiredBalance <- {
              val unexpired = tokenLocks.filter(_.unlockEpoch.forall(_ >= epochProgress))

              unexpired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) { (currentBalanceEither, tokenLock) =>
                for {
                  currentBalance <- currentBalanceEither
                  balanceAfterAmount <- currentBalance.minus(TokenLockAmount.toAmount(tokenLock.amount))
                  balanceAfterFee <- balanceAfterAmount.minus(TokenLockFee.toAmount(tokenLock.fee))
                } yield balanceAfterFee
              }
            }

            expiredBalance <- {
              val expired = tokenLocks.filter(_.unlockEpoch.exists(_ < epochProgress))

              expired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(unexpiredBalance)) { (currentBalanceEither, allowSpend) =>
                for {
                  currentBalance <- currentBalanceEither
                  balanceAfterExpiredAmount <- currentBalance.plus(TokenLockAmount.toAmount(allowSpend.amount))
                } yield balanceAfterExpiredAmount
              }
            }

            finalBalance <-
              acceptedTokenUnlocks.foldLeft[Either[BalanceArithmeticError, Balance]](Right(expiredBalance)) {
                case (currentBalanceEither, tokenUnlock) =>
                  for {
                    currentBalance <- currentBalanceEither
                    balanceAfterUnlock <- currentBalance.plus(TokenLockAmount.toAmount(tokenUnlock.amount))
                  } yield balanceAfterUnlock
              }

            updatedAcc = acc.updated(address, finalBalance)
          } yield updatedAcc
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
    ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] = {
      val expiredCurrencyAllowSpends = filterExpiredAllowSpends(lastActiveCurrencyAllowSpends, epochProgress)

      (incomingCurrencyAllowSpends |+| expiredCurrencyAllowSpends)
        .foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](Right(currentBalances)) {
          case (accEither, (address, allowSpends)) =>
            for {
              acc <- accEither
              initialBalance = acc.getOrElse(address, Balance.empty)
              unexpiredBalance <- {
                val unexpired = allowSpends.filter(_.lastValidEpochProgress >= epochProgress)

                unexpired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) { (currentBalanceEither, allowSpend) =>
                  for {
                    currentBalance <- currentBalanceEither
                    balanceAfterAmount <- currentBalance.minus(SwapAmount.toAmount(allowSpend.amount))
                    balanceAfterFee <- balanceAfterAmount.minus(AllowSpendFee.toAmount(allowSpend.fee))
                  } yield balanceAfterFee
                }
              }
              expiredBalance <- {
                val expired = allowSpends.filter(_.lastValidEpochProgress < epochProgress)
                expired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(unexpiredBalance)) { (currentBalanceEither, allowSpend) =>
                  for {
                    currentBalance <- currentBalanceEither
                    balanceAfterExpiredAmount <- currentBalance.plus(SwapAmount.toAmount(allowSpend.amount))
                  } yield balanceAfterExpiredAmount
                }
              }
              updatedAcc = acc.updated(address, expiredBalance)
            } yield updatedAcc
        }
    }

    private def updateCurrencyBalancesBySpendTransactions(
      currentBalances: SortedMap[Address, Balance],
      allActiveCurrencyAllowSpends: SortedMap[Address, List[Hashed[AllowSpend]]],
      metagraphIdSpendTransactions: List[SpendTransaction]
    ): Either[BalanceArithmeticError, SortedMap[Address, Balance]] =
      metagraphIdSpendTransactions.foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](Right(currentBalances)) {
        (txnAccEither, spendTransaction) =>
          for {
            txnAcc <- txnAccEither
            destinationAddress = spendTransaction.destination
            sourceAddress = spendTransaction.source

            addressAllowSpends = allActiveCurrencyAllowSpends.getOrElse(sourceAddress, List.empty)
            spendTransactionAmount = SwapAmount.toAmount(spendTransaction.amount)
            currentDestinationBalance = txnAcc.getOrElse(destinationAddress, Balance.empty)

            updatedBalances <- spendTransaction.allowSpendRef.flatMap { allowSpendRef =>
              addressAllowSpends.find(_.hash === allowSpendRef)
            } match {
              case Some(allowSpend) =>
                val sourceAllowSpendAddress = allowSpend.source
                val currentSourceBalance = txnAcc.getOrElse(sourceAllowSpendAddress, Balance.empty)
                val balanceToReturnToAddress = allowSpend.amount.value.value - spendTransactionAmount.value.value

                for {
                  updatedDestinationBalance <- currentDestinationBalance.plus(spendTransactionAmount)
                  updatedSourceBalance <- currentSourceBalance.plus(
                    Amount(NonNegLong.from(balanceToReturnToAddress).getOrElse(NonNegLong.MinValue))
                  )
                } yield
                  txnAcc
                    .updated(destinationAddress, updatedDestinationBalance)
                    .updated(sourceAllowSpendAddress, updatedSourceBalance)

              case None =>
                val currentSourceBalance = txnAcc.getOrElse(sourceAddress, Balance.empty)

                for {
                  updatedDestinationBalance <- currentDestinationBalance.plus(spendTransactionAmount)
                  updatedSourceBalance <- currentSourceBalance.minus(spendTransactionAmount)
                } yield
                  txnAcc
                    .updated(destinationAddress, updatedDestinationBalance)
                    .updated(sourceAddress, updatedSourceBalance)
            }
          } yield updatedBalances
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
