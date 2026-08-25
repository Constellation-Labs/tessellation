package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data._
import cats.effect.Async
import cats.syntax.all._
import cats.{Order, Parallel}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSyncView}
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.{FieldsAddedOrdinals, LastGlobalSnapshotsSyncConfig}
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.getFeeAddresses
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
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencyBalanceAdjustments.metagraphsBalancesAdjustments
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
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies
import retry.implicits.retrySyntaxError

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
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    lastGlobalSyncView: Option[GlobalSyncView],
    shouldValidateCollateral: Boolean,
    lastArtifactProofs: NonEmptySet[SignatureProof]
  )(implicit hasher: Hasher[F]): F[CurrencySnapshotAcceptanceResult]

  def acceptRewardTxs(
    baseBalances: SortedMap[Address, Balance],
    newUpdatedBalance: Map[Address, Balance],
    rewards: SortedSet[RewardTransaction]
  ): F[(SortedMap[Address, Balance], SortedSet[RewardTransaction])]
}

object CurrencySnapshotAcceptanceManager {

  /** Applies fee transactions sequentially, dropping any transaction whose source cannot cover it.
    *
    * Every debit and credit goes through `Balance.minus`/`Balance.plus`, which reject underflow and overflow. Accumulating raw `Long`s and
    * checking only the final per-address total allowed a group of transactions to wrap a source balance past `Long.MinValue` back to a
    * non-negative value, crediting destinations with supply that never existed.
    *
    * Unaffordable transactions are dropped rather than failing the snapshot: fee transactions are user-supplied, so raising here would let
    * anyone halt a metagraph.
    *
    * @return
    *   the updated balances, the accepted transactions, and the rejected ones in iteration order
    */
  def applyFeeTransactions(
    balances: SortedMap[Address, Balance],
    txs: SortedSet[Signed[FeeTransaction]]
  ): (SortedMap[Address, Balance], SortedSet[Signed[FeeTransaction]], List[Signed[FeeTransaction]]) = {
    val (feeReferredBalances, rejected) =
      txs.foldLeft((SortedMap.empty[Address, Balance], List.empty[Signed[FeeTransaction]])) {
        case ((acc, rejected), signedTx) =>
          val tx = signedTx.value

          def balanceOf(address: Address, current: SortedMap[Address, Balance]): Balance =
            current.getOrElse(address, balances.getOrElse(address, Balance.empty))

          val applied = for {
            debited <- balanceOf(tx.source, acc).minus(tx.amount)
            afterDebit = acc.updated(tx.source, debited)
            credited <- balanceOf(tx.destination, afterDebit).plus(tx.amount)
          } yield afterDebit.updated(tx.destination, credited)

          applied match {
            case Right(updated) => (updated, rejected)
            case Left(_)        => (acc, signedTx :: rejected)
          }
      }

    (balances ++ feeReferredBalances, txs -- rejected, rejected.reverse)
  }

  /** Legacy raw-Long accumulation retained only for deterministic replay below fixingDataApplicationFeeValidation.
    *
    * The incident snapshot was signed with this behavior. Re-executing it with checked arithmetic drops the four overflowing transactions
    * and produces a different state proof, so historical snapshots must select this path from their signed globalSyncView.
    */
  def applyFeeTransactionsUnchecked(
    balances: SortedMap[Address, Balance],
    txs: SortedSet[Signed[FeeTransaction]]
  ): Either[Throwable, SortedMap[Address, Balance]] = {
    val feeReferredAddresses = txs.flatMap(tx => Set(tx.value.source, tx.value.destination))
    val feeReferredBalances = feeReferredAddresses.foldLeft(SortedMap.empty[Address, Long]) {
      case (acc, address) =>
        acc.updated(address, balances.getOrElse(address, Balance.empty).value.value)
    }
    val updatedFeeReferredBalances = txs.foldLeft(feeReferredBalances) {
      case (current, tx) =>
        current
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
            .leftMap(e => new ArithmeticException(s"Unexpected state when applying fee transactions: $e"): Throwable)
      }
      .map(balances ++ _)
  }

  def make[F[_]: Async: Parallel](
    fieldsAddedOrdinals: FieldsAddedOrdinals,
    environment: AppEnvironment,
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    blockAcceptanceManager: BlockAcceptanceManager[F],
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    collateral: Amount,
    messageValidator: CurrencyMessageValidator[F],
    feeTransactionValidator: FeeTransactionValidator[F],
    globalSnapshotSyncValidator: GlobalSnapshotSyncValidator[F],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
  ): F[CurrencySnapshotAcceptanceManager[F]] = for {

    // Holds a cache of the most recent GlobalIncrementalSnapshots by their SnapshotOrdinal.
    // Used to avoid redundant network calls and repeated deserialization of global snapshots
    // when multiple currency snapshots are being processed concurrently or in sequence.
    lastGlobalSnapshotsCached <- SignallingRef.of[F, Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]](Map.empty)

    // Tracks which global snapshot ordinals have already been processed for each metagraph address.
    // This avoids re-extracting global-layer artifacts such as SpendActions when multiple
    // currency snapshots are produced before lastGlobalSnapshotInfo is updated.
    // Not maintaining this state would result in applying the same actions multiple times,
    // leading to inconsistencies like double deduction and snapshot diff mismatches.
    globalSnapshotsAlreadyProcessed <- SignallingRef.of[F, Map[Address, Map[SnapshotOrdinal, List[SnapshotOrdinal]]]](Map.empty)

  } yield
    make[F](
      fieldsAddedOrdinals,
      environment,
      lastGlobalSnapshotsSyncConfig,
      blockAcceptanceManager,
      tokenLockBlockAcceptanceManager,
      allowSpendBlockAcceptanceManager,
      collateral,
      messageValidator,
      feeTransactionValidator,
      globalSnapshotSyncValidator,
      lastNGlobalSnapshotStorage,
      lastGlobalSnapshotStorage,
      lastGlobalSnapshotsCached,
      globalSnapshotsAlreadyProcessed
    )

  def make[F[_]: Async: Parallel](
    fieldsAddedOrdinals: FieldsAddedOrdinals,
    environment: AppEnvironment,
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    blockAcceptanceManager: BlockAcceptanceManager[F],
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    collateral: Amount,
    messageValidator: CurrencyMessageValidator[F],
    feeTransactionValidator: FeeTransactionValidator[F],
    globalSnapshotSyncValidator: GlobalSnapshotSyncValidator[F],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastGlobalSnapshotsCached: SignallingRef[F, Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]],
    globalSnapshotsAlreadyProcessed: SignallingRef[F, Map[Address, Map[SnapshotOrdinal, List[SnapshotOrdinal]]]]
  ): CurrencySnapshotAcceptanceManager[F] = new CurrencySnapshotAcceptanceManager[F] {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("CurrencySnapshotAcceptanceManager")

    private def getGlobalSnapshotWithRetry(
      ordinal: SnapshotOrdinal,
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
    ): F[Hashed[GlobalIncrementalSnapshot]] = {
      val retryPolicy = RetryPolicies.exponentialBackoff[F](1.second).join(RetryPolicies.limitRetries(5))
      getGlobalSnapshotByOrdinal(ordinal)
        .retryingOnFailuresAndAllErrors(
          wasSuccessful = maybeSnapshot => maybeSnapshot.isDefined.pure[F],
          policy = retryPolicy,
          onFailure = (_, retryDetails) =>
            logger.warn(s"Got None when trying to fetch incremental global snapshot $ordinal {attempt=${retryDetails.retriesSoFar}}"),
          onError = (err, retryDetails) =>
            logger.error(err)(s"Error when trying to fetch incremental global snapshot $ordinal {attempt=${retryDetails.retriesSoFar}}")
        )
        .flatMap {
          case Some(snapshot) => snapshot.pure[F]
          case None =>
            new RuntimeException(s"Global snapshot not found for ordinal $ordinal after retries")
              .raiseError[F, Hashed[GlobalIncrementalSnapshot]]
        }
    }

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
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      maybeLastGlobalSyncView: Option[GlobalSyncView],
      shouldValidateCollateral: Boolean,
      lastArtifactProofs: NonEmptySet[SignatureProof]
    )(implicit hasher: Hasher[F]): F[CurrencySnapshotAcceptanceResult] = for {
      initialTxRef <- TransactionReference.emptyCurrency(lastSnapshotContext.address)
      tokenLockInitialTxRef <- TokenLockReference.emptyCurrency(lastSnapshotContext.address)
      initialAllowSpendRef <- AllowSpendReference.emptyCurrency(lastSnapshotContext.address)
      metagraphId = lastSnapshotContext.address

      checkSyncGlobalSnapshotField = fieldsAddedOrdinals.checkSyncGlobalSnapshotField
        .getOrElse(environment, SnapshotOrdinal.MinValue)
      tessellation3MigrationStartingOrdinal = fieldsAddedOrdinals.tessellation3Migration
        .getOrElse(environment, SnapshotOrdinal.MinValue)
      metagraphSyncDataStartingOrdinal = fieldsAddedOrdinals.metagraphSyncData
        .getOrElse(environment, SnapshotOrdinal.MinValue)
      updatedLastSyncGlobalOrder = fieldsAddedOrdinals.updatedLastSyncGlobalOrder
        .getOrElse(environment, SnapshotOrdinal.MinValue)
      updatedLastSyncGlobalFromPeersInConsensus = fieldsAddedOrdinals.updatedLastSyncGlobalFromPeersInConsensus
        .getOrElse(environment, SnapshotOrdinal.MinValue)
      updatingCombineFunctionSpendActions = fieldsAddedOrdinals.updatingCombineFunctionSpendActions
        .getOrElse(environment, SnapshotOrdinal.MinValue)
      fixingAllowSpendExpiration = fieldsAddedOrdinals.fixingAllowSpendExpiration
        .getOrElse(environment, SnapshotOrdinal.MinValue)
      fixingAllowSpendAndTokenLockValidation = fieldsAddedOrdinals.fixingAllowSpendAndTokenLockValidation
        .getOrElse(environment, SnapshotOrdinal.MinValue)
      fixingAllowSpendDestinationCredit = fieldsAddedOrdinals.fixingAllowSpendDestinationCredit
        .getOrElse(environment, SnapshotOrdinal.MinValue)
      fixingDataApplicationFeeValidation = fieldsAddedOrdinals.fixingDataApplicationFeeValidation
        .getOrElse(environment, SnapshotOrdinal.MinValue)
      // Same gate, same deterministic ordinal, as the data application layer one level up. Below it that layer
      // runs the legacy validator, which checks neither source != destination nor signature exclusivity, so a
      // transaction failing only those still reaches acceptance. Dropping it here while an unpatched node
      // raises would make the two produce different artifacts from the same events during the rollout window.
      validateEveryFeeTransaction =
        maybeLastGlobalSyncView.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue) >= fixingDataApplicationFeeValidation

      acceptanceBlocksResult <- acceptBlocks(
        blocksForAcceptance,
        lastSnapshotContext,
        snapshotOrdinal,
        lastActiveTips,
        lastDeprecatedTips,
        initialTxRef,
        shouldValidateCollateral
      )

      acceptedTransactions = acceptanceBlocksResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

      transactionsRefs = acceptTransactionRefs(
        lastSnapshotContext.snapshotInfo.lastTxRefs,
        acceptanceBlocksResult.contextUpdate.lastTxRefs,
        acceptedTransactions
      )

      rewards <- calculateRewardsFn(acceptedTransactions)

      (updatedBalancesByRewards, acceptedRewardTxs) <- acceptRewardTxs(
        lastSnapshotContext.snapshotInfo.balances,
        acceptanceBlocksResult.contextUpdate.balances,
        rewards
      )

      validatedFeeTxs <- validateFeeTxs(feeTransactionsForAcceptance, validateEveryFeeTransaction)

      (updatedBalancesByFeeTransactions, acceptedFeeTxs) <- acceptFeeTxs(
        updatedBalancesByRewards,
        validatedFeeTxs,
        checkedArithmetic = validateEveryFeeTransaction
      )

      acceptedSharedArtifacts = acceptSharedArtifacts(sharedArtifactsForAcceptance)

      globalSnapshotSyncAcceptanceResult <- acceptGlobalSnapshotSyncs(
        lastSnapshotContext.snapshotInfo.globalSnapshotSyncView,
        globalSnapshotSyncsForAcceptance,
        lastSnapshotContext.address,
        facilitators
      )

      maybeUnsyncLastGlobalSnapshot <- lastGlobalSnapshotStorage.getCombined

      (lastUnsyncGlobalSnapshot, lastUnsyncGlobalSnapshotInfo) <- OptionT
        .fromOption(maybeUnsyncLastGlobalSnapshot)
        .getOrRaise(new IllegalStateException("Could not get the last global snapshot info"))

      messagesAcceptanceResult <- acceptMessages(
        lastSnapshotContext.snapshotInfo.lastMessages,
        messagesForAcceptance,
        lastSnapshotContext.address,
        snapshotOrdinal,
        lastUnsyncGlobalSnapshotInfo
      )

      fallbackOrdinal = lastUnsyncGlobalSnapshot.ordinal

      lastPeersParticipatedOnConsensus = lastArtifactProofs.map(_.id.toPeerId)
      peersToGetSnapshotOrdinalSync =
        if (lastUnsyncGlobalSnapshot.ordinal > updatedLastSyncGlobalFromPeersInConsensus) {
          globalSnapshotSyncAcceptanceResult.contextUpdate.filter {
            case (peerId, _) =>
              lastPeersParticipatedOnConsensus.contains(peerId)
          }
        } else {
          globalSnapshotSyncAcceptanceResult.contextUpdate
        }

      maybeSnapshotOrdinalSync = peersToGetSnapshotOrdinalSync.values
        .map(_.globalSnapshotOrdinal)
        .groupBy(identity)
        .maxByOption {
          case (ordinal, occurrences) =>
            if (lastUnsyncGlobalSnapshot.ordinal > updatedLastSyncGlobalOrder) {
              (occurrences.size, ordinal.value.value)
            } else {
              (occurrences.size, -ordinal.value.value)
            }
        }
        .flatMap { case (ordinal, _) => SnapshotOrdinal(ordinal.value - lastGlobalSnapshotsSyncConfig.syncOffset) }

      lastGlobalSnapshots <- lastNGlobalSnapshotStorage.getLastN
      _ <- logger.debug(s"Metagraph $metagraphId snapshot $snapshotOrdinal - maybeSnapshotOrdinalSync: $maybeSnapshotOrdinalSync")

      ordinalToFetchGlobalSnapshot <- maybeSnapshotOrdinalSync
        .orElse(maybeLastGlobalSyncView.map(_.ordinal))
        .filter(_ =!= SnapshotOrdinal.MinValue)
        .fold {
          logger.warn(
            s"Could not get valid global snapshot ordinal sync, falling back to: ${fallbackOrdinal.show}"
          ) >> fallbackOrdinal.pure[F]
        } { ordinal =>
          ordinal.pure[F]
        }

      lastSyncGlobalSnapshot <-
        lastGlobalSnapshots.find(_.ordinal === ordinalToFetchGlobalSnapshot) match {
          case Some(value) =>
            value.pure[F]
          case None =>
            lastGlobalSnapshotsCached.get.flatMap { cache =>
              cache.get(ordinalToFetchGlobalSnapshot) match {
                case Some(snapshot) => snapshot.pure[F]
                case None           => getGlobalSnapshotWithRetry(ordinalToFetchGlobalSnapshot, getGlobalSnapshotByOrdinal)
              }
            }
        }

      _ <- lastGlobalSnapshotsCached.update { current =>
        val updated = current.updated(lastSyncGlobalSnapshot.ordinal, lastSyncGlobalSnapshot)
        updated.toSeq
          .sortBy(_._1.value.value)
          .takeRight(lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory)
          .toMap
      }

      lastGlobalSnapshotEpochProgress = lastSyncGlobalSnapshot.epochProgress
      lastGlobalSnapshotOrdinal = lastSyncGlobalSnapshot.ordinal

      globalSyncView = maybeLastGlobalSyncView
        .filter(_.ordinal >= lastSyncGlobalSnapshot.ordinal)
        .getOrElse(
          GlobalSyncView(
            lastSyncGlobalSnapshot.ordinal,
            lastSyncGlobalSnapshot.hash,
            lastSyncGlobalSnapshot.epochProgress
          )
        )

      allowSpendBlockAcceptanceResult <- acceptAllowSpendBlocks(
        allowSpendBlocksForAcceptance,
        lastSnapshotContext,
        snapshotOrdinal,
        initialAllowSpendRef,
        shouldValidateCollateral,
        lastUnsyncGlobalSnapshot.ordinal,
        maybeLastGlobalSyncView.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue),
        fixingAllowSpendAndTokenLockValidation,
        fixingAllowSpendDestinationCredit,
        lastGlobalSnapshotEpochProgress
      )

      lastAllowSpendsRefs = lastSnapshotContext.snapshotInfo.lastAllowSpendRefs.getOrElse(SortedMap.empty[Address, AllowSpendReference])

      updatedAllowSpendRefs = acceptAllowSpendRefs(
        lastAllowSpendsRefs,
        allowSpendBlockAcceptanceResult.contextUpdate.lastTxRefs
      )

      acceptanceTokenLockBlocksResult <- acceptTokenLockBlocks(
        tokenLockBlocksForAcceptance,
        lastSnapshotContext,
        snapshotOrdinal,
        tokenLockInitialTxRef,
        shouldValidateCollateral,
        lastUnsyncGlobalSnapshot.ordinal,
        fixingAllowSpendAndTokenLockValidation,
        lastGlobalSnapshotEpochProgress
      )

      tokenLockRefs = acceptTokenLockRefs(
        lastSnapshotContext.snapshotInfo.lastTokenLockRefs.getOrElse(SortedMap.empty[Address, TokenLockReference]),
        acceptanceTokenLockBlocksResult.contextUpdate.lastTokenLocksRefs
      )

      (globalSnapshotsSpendActions, globalSnapshotsProcessed) <- getLastGlobalSnapshotsSpendActions(
        globalSyncView.ordinal,
        lastGlobalSnapshots,
        getGlobalSnapshotByOrdinal,
        metagraphId,
        lastUnsyncGlobalSnapshotInfo,
        snapshotOrdinal,
        lastUnsyncGlobalSnapshot.ordinal,
        updatingCombineFunctionSpendActions
      )

      metagraphIdSpendTransactions = globalSnapshotsSpendActions.flatMap {
        case (_, spendActions) =>
          spendActions
            .flatMap(_.spendTransactions.toList)
            .filter(_.currencyId.exists(_.value == metagraphId))
      }.toList

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

      updatedAllowSpends <-
        acceptCurrencyAllowSpends(
          lastGlobalSnapshotEpochProgress,
          incomingCurrencyAllowSpends,
          lastActiveAllowSpends,
          metagraphIdSpendTransactions,
          lastUnsyncGlobalSnapshot.ordinal,
          fixingAllowSpendExpiration
        )

      updatedBalancesByAllowSpends <- updateCurrencyBalancesByAllowSpends(
        lastGlobalSnapshotEpochProgress,
        updatedBalancesByTokenLocks,
        incomingCurrencyAllowSpends,
        lastActiveAllowSpends,
        metagraphIdSpendTransactions,
        lastUnsyncGlobalSnapshot.ordinal,
        fixingAllowSpendExpiration
      ).flatMap {
        case Right(balances) => balances.pure[F]
        case Left(error) =>
          new RuntimeException(s"Balance arithmetic error updating balances by allow spends: $error")
            .raiseError[F, SortedMap[Address, Balance]]
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
        if (lastUnsyncGlobalSnapshot.ordinal > metagraphSyncDataStartingOrdinal) {
          lastUnsyncGlobalSnapshot.ordinal
        } else if (lastGlobalSnapshotOrdinal <= checkSyncGlobalSnapshotField) {
          lastGlobalSnapshotOrdinal
        } else {
          val fallbackOrdinal = lastGlobalSnapshots.lastOption
            .map(_.ordinal)
            .getOrElse(maybeLastGlobalSyncView.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue))
          if (lastGlobalSnapshotOrdinal === SnapshotOrdinal.MinValue) fallbackOrdinal
          else lastGlobalSnapshotOrdinal
        }

      balanceAdjustments = acceptedSharedArtifacts.collect {
        case balanceAdjustment: BalanceAdjustment => balanceAdjustment
      }

      updatedBalancesByInvalidAddressChecks <-
        // A metagraph may be authorized at several ordinals, so select the block matching this one
        // rather than assuming a single entry. Keying uniquely meant the last block in the resource
        // silently retired every earlier block for the same currency: replaying one of those ordinals
        // applied no adjustment and diverged without raising, and a follow-up adjustment could not be
        // scheduled at all.
        metagraphsBalancesAdjustments
          .getOrElse(lastSnapshotContext.address, List.empty)
          .find(info => info.snapshotOrdinal === snapshotOrdinal && info.environment === environment)
          .fold[F[SortedMap[Address, Balance]]] {
            if (balanceAdjustments.nonEmpty) {
              val unauthorizedError = new RuntimeException(
                s"Metagraph $metagraphId not authorized to perform balance updates on ordinal $snapshotOrdinal"
              )
              Async[F].raiseError(unauthorizedError)
            } else {
              updatedBalancesBySpendTransactions.pure[F]
            }
          } { info =>
            info.balanceAdjustFunction(updatedBalancesBySpendTransactions, balanceAdjustments) match {
              case Right(balances) => balances.pure[F]
              case Left(error)     => Async[F].raiseError(new RuntimeException(s"Balance adjustment failed: $error"))
            }
          }

      csi = CurrencySnapshotInfo(
        if (snapshotOrdinalToCheckFields < tessellation3MigrationStartingOrdinal)
          lastSnapshotContext.snapshotInfo.lastTxRefs ++ acceptanceBlocksResult.contextUpdate.lastTxRefs
        else transactionsRefs,
        updatedBalancesByInvalidAddressChecks,
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

      allowSpendsExpiredEvents <- filterExpiredAllowSpends(
        lastActiveAllowSpends,
        lastGlobalSnapshotEpochProgress,
        metagraphIdSpendTransactions,
        lastUnsyncGlobalSnapshot.ordinal,
        fixingAllowSpendExpiration
      ).flatMap { expiredAllowSpends =>
        emitAllowSpendsExpired(expiredAllowSpends)
      }

      tokenUnlocksEvents <- emitTokenUnlocks(
        acceptedTokenUnlocks,
        expiredTokenLocks
      )

      maybeGlobalSnapshotProcessedEvent: SortedSet[SharedArtifact] =
        if (globalSnapshotsProcessed.nonEmpty)
          SortedSet(GlobalSnapshotsProcessed(globalSnapshotsProcessed))
        else
          SortedSet.empty[SharedArtifact]

    } yield
      CurrencySnapshotAcceptanceResult(
        acceptanceBlocksResult,
        acceptanceTokenLockBlocksResult,
        allowSpendBlockAcceptanceResult,
        messagesAcceptanceResult,
        globalSnapshotSyncAcceptanceResult,
        acceptedRewardTxs,
        acceptedSharedArtifacts ++ allowSpendsExpiredEvents ++ tokenUnlocksEvents ++ maybeGlobalSnapshotProcessedEvent,
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
      metagraphId: Address,
      snapshotOrdinal: SnapshotOrdinal,
      lastUnsyncGlobalSnapshotInfo: GlobalSnapshotInfo
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
            val allFeesAddresses = getFeeAddresses(lastUnsyncGlobalSnapshotInfo)
            val balance = lastUnsyncGlobalSnapshotInfo.balances.getOrElse(message.address, Balance.empty)

            // We should call the validateInitialOwner if the ordinal is 2 and it's the first message
            val validationResult =
              if (snapshotOrdinal === SnapshotOrdinal.unsafeApply(2L) && message.parentOrdinal === MessageOrdinal.MinValue) {
                messageValidator.validateInitialOwner(message, metagraphId, allFeesAddresses)
              } else {
                messageValidator.validate(message, lastMsgs, metagraphId, allFeesAddresses)
              }

            validationResult.flatMap {
              case Validated.Valid(_) =>
                val updatedLastMsgs = lastMsgs.updated(message.messageType, message)
                val updatedToAdd = message :: toAdd

                logger.info(
                  s"Message accepted - " +
                    s"Address: ${message.address}, " +
                    s"MessageType: ${message.messageType}, " +
                    s"ParentOrdinal: ${message.parentOrdinal}, " +
                    s"ProofCount: ${message.proofs.size}, " +
                    s"Balance: ${balance.value}"
                ) >> (updatedLastMsgs, updatedToAdd, toReject).pure[F]

              case Validated.Invalid(errors) =>
                val updatedToReject = message :: toReject

                logger.warn(
                  s"Message rejected - " +
                    s"Address: ${message.address}, " +
                    s"MessageType: ${message.messageType}, " +
                    s"ParentOrdinal: ${message.parentOrdinal}, " +
                    s"ProofCount: ${message.proofs.size}, " +
                    s"Balance: ${balance.value}, " +
                    s"Errors: ${errors.toList.mkString(", ")}"
                ) >> (lastMsgs, toAdd, updatedToReject).pure[F]
            }
        }
        .flatTap {
          case (_, toAdd, toReject) =>
            logger.info(
              s"Message acceptance complete - " +
                s"Total processed: ${messagesForAcceptance.size}, " +
                s"Accepted: ${toAdd.size}, " +
                s"Rejected: ${toReject.size}"
            )
        }
        .map {
          case (contextUpdate, toAdd, toReject) =>
            CurrencyMessagesAcceptanceResult(contextUpdate, toAdd, toReject)
        }
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

    private def getLastGlobalSnapshotsSpendActions(
      globalSnapshotViewOrdinal: SnapshotOrdinal,
      lastGlobalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      currencyId: Address,
      lastGlobalSnapshotInfo: GlobalSnapshotInfo,
      currentCurrencySnapshotOrdinal: SnapshotOrdinal,
      lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
      updatedLastSyncGlobalFromPeersInConsensus: SnapshotOrdinal
    ): F[(SortedMap[Address, List[SpendAction]], SortedSet[SnapshotOrdinal])] = {
      val emptySpendActions = SortedMap.empty[Address, List[SpendAction]]
      val emptyProcessedGlobalSnapshots = SortedSet.empty[SnapshotOrdinal]

      lastGlobalSnapshotInfo.metagraphSyncData match {
        case None => (emptySpendActions, emptyProcessedGlobalSnapshots).pure[F]
        case Some(metagraphSyncData) =>
          metagraphSyncData.get(currencyId) match {
            case None => (emptySpendActions, emptyProcessedGlobalSnapshots).pure[F]
            case Some(syncDataInfo) =>
              for {
                allMetagraphsGlobalSnapshotsAlreadyProcessed <- globalSnapshotsAlreadyProcessed.get

                metagraphOrdinalsByCurrencyOrdinal =
                  allMetagraphsGlobalSnapshotsAlreadyProcessed.getOrElse(currencyId, Map.empty)

                allProcessedOrdinals =
                  metagraphOrdinalsByCurrencyOrdinal.values.flatten.toSet

                alreadyProcessedForCurrentOrdinal =
                  metagraphOrdinalsByCurrencyOrdinal.getOrElse(currentCurrencySnapshotOrdinal, List.empty)

                unappliedGlobalOrdinalsToProcess = syncDataInfo.unappliedGlobalChangeOrdinals
                  .filter(o => o <= globalSnapshotViewOrdinal && !allProcessedOrdinals.contains(o))

                globalOrdinalsToProcess = (alreadyProcessedForCurrentOrdinal ++ unappliedGlobalOrdinalsToProcess).toSet

                result <-
                  if (globalOrdinalsToProcess.isEmpty) {
                    (emptySpendActions, emptyProcessedGlobalSnapshots).pure[F]
                  } else {
                    for {
                      spendActions <- processUnappliedOrdinals(
                        globalOrdinalsToProcess,
                        lastGlobalSnapshots,
                        getGlobalSnapshotByOrdinal,
                        lastUnsyncGlobalSnapshotOrdinal,
                        updatedLastSyncGlobalFromPeersInConsensus
                      )
                      _ <- globalSnapshotsAlreadyProcessed.update { current =>
                        val currentMetagraphProcessedOrdinals = current.getOrElse(currencyId, Map.empty)

                        val updatedMetagraphProcessedOrdinals = currentMetagraphProcessedOrdinals
                          .updated(
                            currentCurrencySnapshotOrdinal,
                            currentMetagraphProcessedOrdinals
                              .getOrElse(currentCurrencySnapshotOrdinal, List.empty)
                              ++ unappliedGlobalOrdinalsToProcess
                          )
                          .view
                          .mapValues(_.distinct.sorted)
                          .toSeq
                          .sortBy(_._1.value.value)
                          .takeRight(lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory)
                          .toMap

                        current.updated(currencyId, updatedMetagraphProcessedOrdinals)
                      }
                    } yield (spendActions, unappliedGlobalOrdinalsToProcess)
                  }
              } yield result
          }
      }
    }

    private def processUnappliedOrdinals(
      unappliedOrdinals: Set[SnapshotOrdinal],
      lastGlobalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
      updatedLastSyncGlobalFromPeersInConsensus: SnapshotOrdinal
    ): F[SortedMap[Address, List[SpendAction]]] = {
      val snapshotCache = lastGlobalSnapshots.map(s => s.ordinal -> s).toMap
      val (cached, missing) = unappliedOrdinals.partition(snapshotCache.contains)

      val fromCache = cached.toList.flatMap { ordinal =>
        snapshotCache.get(ordinal).flatMap(_.spendActions).toList
      }
      val fetchMissing = missing.toList.parTraverse { ordinal =>
        getGlobalSnapshotWithRetry(ordinal, getGlobalSnapshotByOrdinal)
          .map(_.spendActions.getOrElse(SortedMap.empty[Address, List[SpendAction]]))
      }

      fetchMissing.map(fromFetched =>
        combineSpendActions(fromCache ++ fromFetched, lastUnsyncGlobalSnapshotOrdinal, updatedLastSyncGlobalFromPeersInConsensus)
      )
    }

    private def combineSpendActions(
      spendActionsList: List[SortedMap[Address, List[SpendAction]]],
      lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
      updatedLastSyncGlobalFromPeersInConsensus: SnapshotOrdinal
    ): SortedMap[Address, List[SpendAction]] =
      if (lastUnsyncGlobalSnapshotOrdinal > updatedLastSyncGlobalFromPeersInConsensus) {
        spendActionsList
          .reduceOption(_ |+| _)
          .getOrElse(SortedMap.empty)
      } else {
        spendActionsList
          .reduceOption(_ ++ _)
          .getOrElse(SortedMap.empty)
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
      initialTxRef: TransactionReference,
      shouldValidateCollateral: Boolean
    )(implicit hasher: Hasher[F]) = {
      val tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
      val context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.snapshotInfo.balances,
        lastSnapshotContext.snapshotInfo.lastTxRefs,
        tipUsages,
        collateral,
        initialTxRef
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context, snapshotOrdinal, shouldValidateCollateral)
    }

    private def acceptTokenLockBlocks(
      tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
      lastSnapshotContext: CurrencySnapshotContext,
      snapshotOrdinal: SnapshotOrdinal,
      initialTxRef: TokenLockReference,
      shouldValidateCollateral: Boolean,
      lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
      fixingAllowSpendAndTokenLockValidation: SnapshotOrdinal,
      lastSyncGlobalSnapshotEpochProgress: EpochProgress
    )(implicit hasher: Hasher[F]) = {
      val context = TokenLockBlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.snapshotInfo.balances,
        lastSnapshotContext.snapshotInfo.lastTokenLockRefs.getOrElse(SortedMap.empty),
        collateral,
        initialTxRef
      )

      if (lastUnsyncGlobalSnapshotOrdinal > fixingAllowSpendAndTokenLockValidation) {
        tokenLockBlockAcceptanceManager.acceptBlocksIteratively(
          tokenLockBlocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldValidateCollateral,
          lastSyncGlobalSnapshotEpochProgress.some
        )
      } else {
        tokenLockBlockAcceptanceManager.acceptBlocksIteratively(
          tokenLockBlocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldValidateCollateral,
          none
        )
      }
    }

    private def acceptAllowSpendBlocks(
      blocksForAcceptance: List[Signed[AllowSpendBlock]],
      lastSnapshotContext: CurrencySnapshotContext,
      snapshotOrdinal: SnapshotOrdinal,
      initialTxRef: AllowSpendReference,
      shouldValidateCollateral: Boolean,
      lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
      lastGlobalSyncViewOrdinal: SnapshotOrdinal,
      fixingAllowSpendAndTokenLockValidation: SnapshotOrdinal,
      fixingAllowSpendDestinationCredit: SnapshotOrdinal,
      lastSyncGlobalSnapshotEpochProgress: EpochProgress
    )(implicit hasher: Hasher[F]) = {
      val context = AllowSpendBlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.snapshotInfo.balances,
        lastSnapshotContext.snapshotInfo.lastAllowSpendRefs.getOrElse(Map.empty),
        collateral,
        initialTxRef
      )
      // Deliberately not lastUnsyncGlobalSnapshotOrdinal, which the sibling gate on the line below uses: that
      // is a live read of the node's own global head, so a node replaying an old snapshot today would evaluate
      // the gate against today's head and apply the current rule to old history. lastGlobalSyncViewOrdinal is
      // carried by the previous currency snapshot, so it is the same on every node and at every replay.
      val creditDestination = lastGlobalSyncViewOrdinal < fixingAllowSpendDestinationCredit
      if (lastUnsyncGlobalSnapshotOrdinal > fixingAllowSpendAndTokenLockValidation) {
        allowSpendBlockAcceptanceManager.acceptBlocksIteratively(
          blocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldValidateCollateral,
          lastSyncGlobalSnapshotEpochProgress.some,
          creditDestination
        )
      } else {
        allowSpendBlockAcceptanceManager.acceptBlocksIteratively(
          blocksForAcceptance,
          context,
          snapshotOrdinal,
          shouldValidateCollateral,
          none,
          creditDestination
        )
      }
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

    // At or above the activation ordinal, drops the transactions that fail validation rather than raising, for
    // the same reason applyFeeTransactions drops unaffordable ones: fee transactions are user-supplied, and
    // raising fails the snapshot on a value an attacker chose. A self-addressed fee transaction, or one
    // carrying a second signature, passes the data-application validators and is rejected only here.
    //
    // Below the activation ordinal it keeps raising. The drop changes which artifact this method produces from
    // the same events, and the data application layer is on its legacy rules down there -- it checks neither
    // source != destination nor signature exclusivity -- so such a transaction still reaches acceptance. A
    // patched node dropping it while an unpatched node raises would split the rollout window.
    private def validateFeeTxs(
      maybeTxs: Option[SortedSet[Signed[FeeTransaction]]],
      dropInvalid: Boolean
    ): F[Option[SortedSet[Signed[FeeTransaction]]]] =
      if (!dropInvalid)
        NonEmptyList.fromList(maybeTxs.toList.flatMap(_.toList)).fold(maybeTxs.pure[F]) { nonEmptyTxs =>
          feeTransactionValidator.validate(nonEmptyTxs).flatMap {
            case Validated.Valid(_) =>
              maybeTxs.pure[F]
            case Validated.Invalid(errors) =>
              new Exception(s"FeeTransaction validation failed: ${errors.toList.mkString(", ")}")
                .raiseError[F, Option[SortedSet[Signed[FeeTransaction]]]]
          }
        }
      else
        maybeTxs.traverse { txs =>
          txs.toList.traverseFilter { signedTx =>
            feeTransactionValidator.validate(signedTx).flatMap {
              case Validated.Valid(_) =>
                signedTx.some.pure[F]
              case Validated.Invalid(errors) =>
                logger
                  .warn(
                    s"Dropped fee transaction from ${signedTx.value.source.show} to ${signedTx.value.destination.show} of " +
                      s"${signedTx.value.amount.value.value}: ${errors.toList.mkString(", ")}"
                  )
                  .as(none[Signed[FeeTransaction]])
            }
          }.map(SortedSet.from(_))
        }

    private def acceptFeeTxs(
      balances: SortedMap[Address, Balance],
      maybeTxs: Option[SortedSet[Signed[FeeTransaction]]],
      checkedArithmetic: Boolean
    ): F[(SortedMap[Address, Balance], Option[SortedSet[Signed[FeeTransaction]]])] =
      maybeTxs match {
        case None => (balances, maybeTxs).pure[F]
        case Some(txs) if !checkedArithmetic =>
          applyFeeTransactionsUnchecked(balances, txs).liftTo[F].map((_, txs.some))
        case Some(txs) =>
          val (updatedBalances, acceptedTxs, rejectedTxs) = applyFeeTransactions(balances, txs)

          rejectedTxs.traverse_ { signedTx =>
            logger.warn(
              s"Rejected fee transaction from ${signedTx.value.source} to ${signedTx.value.destination} of " +
                s"${signedTx.value.amount.value.value}: source balance insufficient or destination balance overflow"
            )
          }
            .as((updatedBalances, acceptedTxs.some))
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

            unlocksForAddress = acceptedTokenUnlocks.filter(_.source == address)
            finalBalance <-
              unlocksForAddress.foldLeft[Either[BalanceArithmeticError, Balance]](Right(expiredBalance)) {
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
      epochProgress: EpochProgress,
      metagraphIdSpendTransactions: List[SpendTransaction],
      lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
      fixingAllowSpendExpiration: SnapshotOrdinal
    )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[Signed[AllowSpend]]]] =
      if (lastUnsyncGlobalSnapshotOrdinal > fixingAllowSpendExpiration) {
        val spentAllowSpendHashes = metagraphIdSpendTransactions.flatMap(_.allowSpendRef).toSet
        for {
          filteredList <- allowSpends.toList.traverse {
            case (address, signedAllowSpends) =>
              signedAllowSpends.toList.traverse { signedAllowSpend =>
                for {
                  hashedAllowSpend <- signedAllowSpend.toHashed
                } yield {
                  val allowSpend = signedAllowSpend.value
                  val isNotExpired = epochProgress > allowSpend.lastValidEpochProgress
                  val isNotSpent = !spentAllowSpendHashes.contains(hashedAllowSpend.hash)

                  if (isNotExpired && isNotSpent) Some(signedAllowSpend) else None
                }
              }.map(_.flatten.to(SortedSet)).map(address -> _)
          }
        } yield filteredList.to(SortedMap)
      } else {
        allowSpends.view.mapValues(_.filter(_.lastValidEpochProgress < epochProgress)).to(SortedMap).pure
      }

    private def filterExpiredTokenLocks(
      tokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      epochProgress: EpochProgress
    ): SortedMap[Address, SortedSet[Signed[TokenLock]]] =
      tokenLocks.view.mapValues(_.filter(_.unlockEpoch.exists(_ < epochProgress))).to(SortedMap)

    private def acceptCurrencyAllowSpends(
      epochProgress: EpochProgress,
      incomingCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      existentCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      allAcceptedSpendTxns: List[SpendTransaction],
      lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
      fixingAllowSpendExpiration: SnapshotOrdinal
    )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[Signed[AllowSpend]]]] = {
      val allAcceptedSpendTxnsAllowSpendsRefs =
        allAcceptedSpendTxns
          .flatMap(_.allowSpendRef)

      for {
        expiredAllowSpends <- filterExpiredAllowSpends(
          existentCurrencyAllowSpends,
          epochProgress,
          allAcceptedSpendTxns,
          lastUnsyncGlobalSnapshotOrdinal,
          fixingAllowSpendExpiration
        )

        unexpiredAllowSpends = (incomingCurrencyAllowSpends |+| expiredAllowSpends).foldLeft(existentCurrencyAllowSpends) {
          case (acc, (address, allowSpends)) =>
            val lastAddressAllowSpends = acc.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])
            val unexpired = (lastAddressAllowSpends ++ allowSpends).filter(_.value.lastValidEpochProgress >= epochProgress)
            acc + (address -> unexpired)
        }

        result <- unexpiredAllowSpends.toList.foldLeftM(unexpiredAllowSpends) {
          case (acc, (address, allowSpends)) =>
            allowSpends.toList.traverse(_.toHashed).map { hashedAllowSpends =>
              val validAllowSpends = hashedAllowSpends
                .filterNot(h => allAcceptedSpendTxnsAllowSpendsRefs.contains(h.hash))
                .map(_.signed)
                .to(SortedSet)

              acc + (address -> validAllowSpends)
            }
        }
      } yield result
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
      lastActiveCurrencyAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      metagraphIdSpendTransactions: List[SpendTransaction],
      lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
      fixingAllowSpendExpiration: SnapshotOrdinal
    )(implicit hasher: Hasher[F]): F[Either[BalanceArithmeticError, SortedMap[Address, Balance]]] =
      for {
        expiredCurrencyAllowSpends <- filterExpiredAllowSpends(
          lastActiveCurrencyAllowSpends,
          epochProgress,
          metagraphIdSpendTransactions,
          lastUnsyncGlobalSnapshotOrdinal,
          fixingAllowSpendExpiration
        )

        result = (incomingCurrencyAllowSpends |+| expiredCurrencyAllowSpends)
          .foldLeft[Either[BalanceArithmeticError, SortedMap[Address, Balance]]](Right(currentBalances)) {
            case (accEither, (address, allowSpends)) =>
              for {
                acc <- accEither
                initialBalance = acc.getOrElse(address, Balance.empty)
                unexpiredBalance <- {
                  val unexpired = allowSpends.filter(_.value.lastValidEpochProgress >= epochProgress)

                  unexpired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) {
                    (currentBalanceEither, signedAllowSpend) =>
                      val allowSpend = signedAllowSpend.value
                      for {
                        currentBalance <- currentBalanceEither
                        balanceAfterAmount <- currentBalance.minus(SwapAmount.toAmount(allowSpend.amount))
                        balanceAfterFee <- balanceAfterAmount.minus(AllowSpendFee.toAmount(allowSpend.fee))
                      } yield balanceAfterFee
                  }
                }
                expiredBalance <- {
                  val expired = allowSpends.filter(_.value.lastValidEpochProgress < epochProgress)
                  expired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(unexpiredBalance)) {
                    (currentBalanceEither, signedAllowSpend) =>
                      val allowSpend = signedAllowSpend.value
                      for {
                        currentBalance <- currentBalanceEither
                        balanceAfterExpiredAmount <- currentBalance.plus(SwapAmount.toAmount(allowSpend.amount))
                      } yield balanceAfterExpiredAmount
                  }
                }
                updatedAcc = acc.updated(address, expiredBalance)
              } yield updatedAcc
          }
      } yield result

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
