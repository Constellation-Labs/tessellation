package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.Parallel
import cats.data.{NonEmptySet, OptionT}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSyncView}
import io.constellationnetwork.currency.validations.FeeTransactionSignatureValidator.isEnabled
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.{FieldsAddedOrdinals, LastGlobalSnapshotsSyncConfig}
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.snapshot.programs.SnapshotFailure
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.transaction.FeeTransactionValidator
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencyBalanceAdjustments.metagraphsBalancesAdjustments
import io.constellationnetwork.node.shared.infrastructure.snapshot.{
  CurrencyMessageValidator,
  GlobalSnapshotSyncValidator,
  RecoveryGlobalSnapshotSync
}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.currencyMessage.CurrencyMessage
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.syntax.sortedCollection.{sortedMapSyntax, sortedSetSyntax}

import eu.timepit.refined.auto.autoUnwrap
import fs2.concurrent.SignallingRef

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
    shouldPerformMetagraphSpecificValidations: Boolean,
    lastArtifactProofs: NonEmptySet[SignatureProof],
    previouslyProcessedGlobalSnapshots: SortedSet[SnapshotOrdinal],
    recoveryHistoryMarkerPresent: Boolean,
    historicalDependencyResolution: Boolean,
    expectedRecoveryHistoryMarker: Boolean
  )(implicit hasher: Hasher[F]): F[CurrencySnapshotAcceptanceResult]

  def acceptRewardTxs(
    baseBalances: SortedMap[Address, Balance],
    newUpdatedBalance: Map[Address, Balance],
    rewards: SortedSet[RewardTransaction]
  ): F[(SortedMap[Address, Balance], SortedSet[RewardTransaction])]
}

object CurrencySnapshotAcceptanceManager {

  /** A validated recovery reset is an authoritative lineage replacement, not an ordinary monotonic update. Its canonical retained-window
    * target must therefore replace even a numerically newer parent view, which may name an orphaned GL0 branch. Ordinary snapshots retain
    * the legacy non-regression rule.
    */
  private[currency] def selectGlobalSyncView(
    previous: Option[GlobalSyncView],
    resolved: GlobalSyncView,
    isRecoveryReset: Boolean
  ): GlobalSyncView =
    if (isRecoveryReset) resolved
    else previous.filter(_.ordinal >= resolved.ordinal).getOrElse(resolved)

  def make[F[_]: Async: Parallel: JsonSerializer: Metrics](
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
  )(
    implicit currencyStateProofSelector: CurrencyStateProofSelector
  ): F[CurrencySnapshotAcceptanceManager[F]] = for {
    globalSnapshotsAlreadyProcessed <- SignallingRef.of[F, Map[Address, Map[SnapshotOrdinal, List[SnapshotOrdinal]]]](Map.empty)
    blockOps = BlockAcceptanceOpsManager.make[F](
      blockAcceptanceManager,
      tokenLockBlockAcceptanceManager,
      allowSpendBlockAcceptanceManager,
      collateral
    )

    messageOps = MessageValidationOpsManager.make[F](
      messageValidator,
      globalSnapshotSyncValidator
    )

    globalSnapshotOps = GlobalSnapshotOpsManager.make[F](
      lastGlobalSnapshotsSyncConfig,
      globalSnapshotsAlreadyProcessed
    )

    allowSpendOps = AllowSpendOpsManager.make[F]
    tokenLockOps = TokenLockOpsManager.make[F]
    balanceOps = BalanceOpsManager.make[F](feeTransactionValidator)

  } yield
    new CurrencySnapshotAcceptanceManagerImpl[F](
      fieldsAddedOrdinals,
      environment,
      lastGlobalSnapshotsSyncConfig,
      lastNGlobalSnapshotStorage,
      lastGlobalSnapshotStorage,
      blockOps,
      messageOps,
      globalSnapshotOps,
      allowSpendOps,
      tokenLockOps,
      balanceOps
    ): CurrencySnapshotAcceptanceManager[F]
}

/** Main implementation with parallelized operations for improved performance
  */
private class CurrencySnapshotAcceptanceManagerImpl[F[_]: Async: Parallel: JsonSerializer](
  fieldsAddedOrdinals: FieldsAddedOrdinals,
  environment: AppEnvironment,
  lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
  lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
  lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  blockOps: BlockAcceptanceOpsManager[F],
  messageOps: MessageValidationOpsManager[F],
  globalSnapshotOps: GlobalSnapshotOpsManager[F],
  allowSpendOps: AllowSpendOpsManager[F],
  tokenLockOps: TokenLockOpsManager[F],
  balanceOps: BalanceOpsManager[F]
)(implicit currencyStateProofSelector: CurrencyStateProofSelector)
    extends CurrencySnapshotAcceptanceManager[F] {
  private val feeTransactionSecurityActivationOrdinal = fieldsAddedOrdinals.feeTransactionSecurityFor(environment)

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
    shouldPerformMetagraphSpecificValidations: Boolean,
    lastArtifactProofs: NonEmptySet[SignatureProof],
    previouslyProcessedGlobalSnapshots: SortedSet[SnapshotOrdinal],
    recoveryHistoryMarkerPresent: Boolean,
    historicalDependencyResolution: Boolean,
    expectedRecoveryHistoryMarker: Boolean
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

    acceptanceBlocksResult <- blockOps.acceptBlocks(
      blocksForAcceptance,
      lastSnapshotContext,
      snapshotOrdinal,
      lastActiveTips,
      lastDeprecatedTips,
      initialTxRef,
      shouldPerformMetagraphSpecificValidations
    )

    acceptedTransactions = acceptanceBlocksResult.accepted.flatMap {
      case (block, _) =>
        block.value.transactions.toSortedSet
    }.toSortedSet

    transactionsRefs = blockOps.acceptTransactionRefs(
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

    _ <- balanceOps.validateFeeTxs(
      feeTransactionsForAcceptance,
      isEnabled(
        maybeLastGlobalSyncView.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue),
        feeTransactionSecurityActivationOrdinal
      )
    )

    (updatedBalancesByFeeTransactions, acceptedFeeTxs) <- balanceOps.acceptFeeTxs(
      updatedBalancesByRewards,
      feeTransactionsForAcceptance
    )

    // SnapshotOrdinal.MaxValue is a protocol-reserved epoch marker. A data application or
    // custom-artifact producer cannot activate recovery semantics by injecting it.
    callerSharedArtifacts = sharedArtifactsForAcceptance.filterNot(ProcessedGlobalSnapshotHistory.containsReservedMarker)
    maybeUnsyncLastGlobalSnapshot <- lastGlobalSnapshotStorage.getCombined

    (lastUnsyncGlobalSnapshot, lastUnsyncGlobalSnapshotInfo) <- OptionT
      .fromOption(maybeUnsyncLastGlobalSnapshot)
      .getOrRaise(new IllegalStateException("Could not get the last global snapshot info"))

    lastUnsyncBalances = lastUnsyncGlobalSnapshotInfo.balances
    lastUnsyncLastCurrencySnapshots = lastUnsyncGlobalSnapshotInfo.lastCurrencySnapshots
    lastUnsyncMetagraphSyncData = lastUnsyncGlobalSnapshotInfo.metagraphSyncData
    lastGlobalSnapshots <- lastNGlobalSnapshotStorage.getLastN
    inheritedGlobalSnapshotSyncs = lastSnapshotContext.snapshotInfo.globalSnapshotSyncView.getOrElse(
      SortedMap.empty[PeerId, Signed[GlobalSnapshotSync]]
    )
    recoveryResetContext = lastUnsyncMetagraphSyncData.flatMap(_.get(metagraphId)).map { syncData =>
      RecoveryGlobalSnapshotSync.ValidationContext(
        currentSigners = facilitators,
        inheritedPeerIds = inheritedGlobalSnapshotSyncs.keySet,
        inheritedSessions = inheritedGlobalSnapshotSyncs.view.mapValues(_.session).to(SortedMap),
        currentGlobalParent = lastUnsyncGlobalSnapshot.ordinal,
        recentGlobalSnapshots = SortedMap.from(lastGlobalSnapshots.map(snapshot => snapshot.ordinal -> snapshot.hash)),
        retainedCount = lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory.value,
        syncOffset = lastGlobalSnapshotsSyncConfig.syncOffset.value,
        metagraphLastAcceptedOn = syncData.globalOrdinalLastAcceptedOn,
        unappliedGlobalChangeOrdinals = syncData.unappliedGlobalChangeOrdinals
      )
    }
    resetRecognitionEnabled = recoveryHistoryMarkerPresent || expectedRecoveryHistoryMarker

    parallelResults <- (
      messageOps.acceptMessages(
        lastSnapshotContext.snapshotInfo.lastMessages,
        messagesForAcceptance,
        lastSnapshotContext.address,
        snapshotOrdinal,
        lastUnsyncBalances,
        lastUnsyncLastCurrencySnapshots,
        shouldPerformMetagraphSpecificValidations
      ),
      messageOps.acceptGlobalSnapshotSyncs(
        lastSnapshotContext.snapshotInfo.globalSnapshotSyncView,
        globalSnapshotSyncsForAcceptance,
        lastSnapshotContext.address,
        facilitators,
        recoveryResetContext,
        resetRecognitionEnabled
      )
    ).parMapN((messages, syncs) => (messages, syncs))

    (messagesAcceptanceResult, globalSnapshotSyncAcceptanceResult) = parallelResults
    recoveryEpochActive = recoveryHistoryMarkerPresent || globalSnapshotSyncAcceptanceResult.isRecoveryReset
    _ <- ProcessedGlobalSnapshotHistory
      .RecoveryHistoryMarkerMismatch(expectedRecoveryHistoryMarker, recoveryEpochActive)
      .raiseError[F, Unit]
      .whenA(historicalDependencyResolution && expectedRecoveryHistoryMarker =!= recoveryEpochActive)
    // The signed marker is the semantic boundary, including while GL0 recreates historical
    // artifacts. Only unmarked rc.12 history may use its legacy archive/network callback;
    // allowing a marked artifact to do so would reintroduce node-local archive availability as
    // a consensus input during replay.
    dependencyMode = GlobalSnapshotOpsManager.selectDependencyMode(historicalDependencyResolution, recoveryEpochActive)
    // Before the reset marker, preserve rc.12 replay byte-for-byte: historical artifacts supplied their own
    // GlobalSnapshotsProcessed value and the legacy process cache might or might not re-emit it. Once the
    // deterministic recovery epoch starts, the value is derived below exclusively from signed parent history.
    acceptedSharedArtifacts =
      if (recoveryEpochActive) callerSharedArtifacts.filterNot(_.isInstanceOf[GlobalSnapshotsProcessed])
      else callerSharedArtifacts

    fallbackOrdinal = lastUnsyncGlobalSnapshot.ordinal

    lastPeersParticipatedOnConsensus = lastArtifactProofs.map(_.id.toPeerId)
    peersToGetSnapshotOrdinalSync =
      if (globalSnapshotSyncAcceptanceResult.isRecoveryReset) {
        globalSnapshotSyncAcceptanceResult.contextUpdate
      } else if (lastUnsyncGlobalSnapshot.ordinal > updatedLastSyncGlobalFromPeersInConsensus) {
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

    ordinalToFetchGlobalSnapshot <- maybeSnapshotOrdinalSync
      .orElse(maybeLastGlobalSyncView.map(_.ordinal))
      .filter(_ =!= SnapshotOrdinal.MinValue)
      .fold(fallbackOrdinal.pure[F])(_.pure[F])

    lastSyncGlobalSnapshot <- globalSnapshotOps.resolveGlobalSnapshot(
      HistoricalGlobalSnapshotResolver.SyncTarget,
      ordinalToFetchGlobalSnapshot,
      lastUnsyncGlobalSnapshot.ordinal,
      lastGlobalSnapshots,
      getGlobalSnapshotByOrdinal,
      dependencyMode
    )

    lastGlobalSnapshotEpochProgress = lastSyncGlobalSnapshot.epochProgress
    lastGlobalSnapshotOrdinal = lastSyncGlobalSnapshot.ordinal

    resolvedGlobalSyncView = GlobalSyncView(
      lastSyncGlobalSnapshot.ordinal,
      lastSyncGlobalSnapshot.hash,
      lastSyncGlobalSnapshot.epochProgress
    )
    globalSyncView = CurrencySnapshotAcceptanceManager.selectGlobalSyncView(
      maybeLastGlobalSyncView,
      resolvedGlobalSyncView,
      globalSnapshotSyncAcceptanceResult.isRecoveryReset
    )

    blockAcceptanceResults <- (
      blockOps.acceptTokenLockBlocks(
        tokenLockBlocksForAcceptance,
        lastSnapshotContext,
        snapshotOrdinal,
        tokenLockInitialTxRef,
        shouldPerformMetagraphSpecificValidations,
        lastUnsyncGlobalSnapshot.ordinal,
        fixingAllowSpendAndTokenLockValidation,
        lastGlobalSnapshotEpochProgress
      ),
      blockOps.acceptAllowSpendBlocks(
        allowSpendBlocksForAcceptance,
        lastSnapshotContext,
        snapshotOrdinal,
        initialAllowSpendRef,
        shouldPerformMetagraphSpecificValidations,
        lastUnsyncGlobalSnapshot.ordinal,
        fixingAllowSpendAndTokenLockValidation,
        lastGlobalSnapshotEpochProgress
      )
    ).parMapN((tokenLock, allowSpend) => (tokenLock, allowSpend))

    (acceptanceTokenLockBlocksResult, allowSpendBlockAcceptanceResult) = blockAcceptanceResults

    lastAllowSpendsRefs = lastSnapshotContext.snapshotInfo.lastAllowSpendRefs.getOrElse(SortedMap.empty[Address, AllowSpendReference])

    updatedAllowSpendRefs = blockOps.acceptAllowSpendRefs(
      lastAllowSpendsRefs,
      allowSpendBlockAcceptanceResult.contextUpdate.lastTxRefs
    )

    tokenLockRefs = blockOps.acceptTokenLockRefs(
      lastSnapshotContext.snapshotInfo.lastTokenLockRefs.getOrElse(SortedMap.empty[Address, TokenLockReference]),
      acceptanceTokenLockBlocksResult.contextUpdate.lastTokenLocksRefs
    )

    (globalSnapshotsSpendActions, globalSnapshotsProcessed) <- globalSnapshotOps.getLastGlobalSnapshotsSpendActions(
      globalSyncView.ordinal,
      lastGlobalSnapshots,
      getGlobalSnapshotByOrdinal,
      metagraphId,
      lastUnsyncMetagraphSyncData,
      snapshotOrdinal,
      maybeLastGlobalSyncView,
      previouslyProcessedGlobalSnapshots,
      lastUnsyncGlobalSnapshot.ordinal,
      updatingCombineFunctionSpendActions,
      dependencyMode,
      deterministicProcessedHistory = recoveryEpochActive
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

    acceptedTokenUnlocks = tokenLockOps.acceptTokenUnlocks(
      expiredTokenLocksHashes,
      tokenUnlocks,
      tokenLocksRefs
    )

    acceptedTokenLocks = incomingTokenLocks
      .filter(itl => itl.unlockEpoch.forall(_ >= lastGlobalSnapshotEpochProgress))
      .groupBy(_.source)
      .toSortedMap

    (updatedActiveTokenLocks, expiredTokenLocks) <- tokenLockOps.acceptTokenLocks(
      lastGlobalSnapshotEpochProgress,
      acceptedTokenLocks,
      activeTokenLocks,
      acceptedTokenUnlocks
    )

    updatedBalancesByTokenLocks <- Async[F].fromEither(
      tokenLockOps
        .updateBalancesByTokenLocks(
          lastGlobalSnapshotEpochProgress,
          updatedBalancesByFeeTransactions,
          acceptedTokenLocks,
          activeTokenLocks,
          acceptedTokenUnlocks
        )
        .leftMap(error => SnapshotFailure.BalanceArithmeticError.TokenLocks(error.toString))
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

    updatedAllowSpends <- allowSpendOps.acceptCurrencyAllowSpends(
      lastGlobalSnapshotEpochProgress,
      incomingCurrencyAllowSpends,
      lastActiveAllowSpends,
      metagraphIdSpendTransactions,
      lastUnsyncGlobalSnapshot.ordinal,
      fixingAllowSpendExpiration
    )

    updatedBalancesByAllowSpends <- allowSpendOps
      .updateCurrencyBalancesByAllowSpends(
        lastGlobalSnapshotEpochProgress,
        updatedBalancesByTokenLocks,
        incomingCurrencyAllowSpends,
        lastActiveAllowSpends,
        metagraphIdSpendTransactions,
        lastUnsyncGlobalSnapshot.ordinal,
        fixingAllowSpendExpiration
      )
      .flatMap {
        case Right(balances) => balances.pure[F]
        case Left(error) =>
          SnapshotFailure.BalanceArithmeticError
            .AllowSpends(error.toString)
            .raiseError[F, SortedMap[Address, Balance]]
      }

    allActiveCurrencyAllowSpends <- (incomingCurrencyAllowSpends |+| lastActiveAllowSpends).toList.traverse {
      case (address, allowSpends) =>
        allowSpends.toList.traverse(_.toHashed).map(hashedAllowSpends => address -> hashedAllowSpends)
    }.map(_.toSortedMap)

    updatedBalancesBySpendTransactions <- Async[F].fromEither(
      allowSpendOps
        .updateCurrencyBalancesBySpendTransactions(
          updatedBalancesByAllowSpends,
          allActiveCurrencyAllowSpends,
          metagraphIdSpendTransactions
        )
        .leftMap(error => SnapshotFailure.BalanceArithmeticError.SpendTransactions(error.toString))
    )

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
      metagraphsBalancesAdjustments
        .get(lastSnapshotContext.address)
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
          if (info.snapshotOrdinal === snapshotOrdinal && info.environment === environment) {
            info.balanceAdjustFunction(updatedBalancesBySpendTransactions, balanceAdjustments) match {
              case Right(balances) => balances.pure[F]
              case Left(error)     => Async[F].raiseError(new RuntimeException(s"Balance adjustment failed: $error"))
            }
          } else {
            updatedBalancesBySpendTransactions.pure[F]
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

    events <- (
      allowSpendOps
        .filterExpiredAllowSpends(
          lastActiveAllowSpends,
          lastGlobalSnapshotEpochProgress,
          metagraphIdSpendTransactions,
          lastUnsyncGlobalSnapshot.ordinal,
          fixingAllowSpendExpiration
        )
        .flatMap(allowSpendOps.emitAllowSpendsExpired),
      tokenLockOps.emitTokenUnlocks(acceptedTokenUnlocks, expiredTokenLocks)
    ).parMapN((allowSpendEvents, tokenLockEvents) => (allowSpendEvents, tokenLockEvents))

    (allowSpendsExpiredEvents, tokenUnlocksEvents) = events

    globalSnapshotProcessedEvents: SortedSet[SharedArtifact] = {
      val payload =
        if (globalSnapshotsProcessed.nonEmpty)
          SortedSet[SharedArtifact](GlobalSnapshotsProcessed(globalSnapshotsProcessed))
        else
          SortedSet.empty[SharedArtifact]

      if (recoveryEpochActive) payload + ProcessedGlobalSnapshotHistory.Marker else payload
    }

  } yield
    CurrencySnapshotAcceptanceResult(
      acceptanceBlocksResult,
      acceptanceTokenLockBlocksResult,
      allowSpendBlockAcceptanceResult,
      messagesAcceptanceResult,
      globalSnapshotSyncAcceptanceResult,
      acceptedRewardTxs,
      acceptedSharedArtifacts ++ allowSpendsExpiredEvents ++ tokenUnlocksEvents ++ globalSnapshotProcessedEvents,
      acceptedFeeTxs,
      csi,
      stateProof,
      globalSyncView,
      lastGlobalSnapshotOrdinal,
      snapshotOrdinalToCheckFields
    )

  def acceptRewardTxs(
    baseBalances: SortedMap[Address, Balance],
    newUpdatedBalance: Map[Address, Balance],
    rewards: SortedSet[RewardTransaction]
  ): F[(SortedMap[Address, Balance], SortedSet[RewardTransaction])] =
    balanceOps.acceptRewardTxs(baseBalances, newUpdatedBalance, rewards)
}
