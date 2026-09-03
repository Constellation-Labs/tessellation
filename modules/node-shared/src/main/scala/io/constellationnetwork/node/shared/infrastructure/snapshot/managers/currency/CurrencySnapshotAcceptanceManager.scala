package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.Parallel
import cats.data.{NonEmptySet, OptionT}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.schema.CurrencySnapshotSemantics
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
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.currencyMessage.CurrencyMessage
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.SnapshotVersion
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.syntax.sortedCollection.{sortedMapSyntax, sortedSetSyntax}

import eu.timepit.refined.auto._
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
    historicalDependencyResolution: Boolean,
    parentSnapshotVersion: SnapshotVersion,
    allowSpendBlockAcceptanceMode: AllowSpendBlockAcceptanceMode
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

  // This manager is also embedded by snapshot-streaming, which has no Metrics runtime.
  // Node applications pass Some(Metrics[F]); library-only consumers retain the legacy source API.
  def make[F[_]: Async: Parallel: JsonSerializer](
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
    metrics: Option[Metrics[F]] = None
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
      globalSnapshotSyncValidator,
      metrics
    )

    globalSnapshotOps = GlobalSnapshotOpsManager.make[F](
      lastGlobalSnapshotsSyncConfig,
      globalSnapshotsAlreadyProcessed,
      metrics
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
      balanceOps,
      metrics
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
  balanceOps: BalanceOpsManager[F],
  metrics: Option[Metrics[F]]
)(implicit currencyStateProofSelector: CurrencyStateProofSelector)
    extends CurrencySnapshotAcceptanceManager[F] {
  private val feeTransactionSecurityActivationOrdinal = fieldsAddedOrdinals.feeTransactionSecurityFor(environment)
  private val currencySnapshotProtocolV1ActivationOrdinal = fieldsAddedOrdinals.currencySnapshotProtocolV1For(environment)
  // Same boundary the data application layer uses for `validateEveryFeeTransaction`, so both layers agree on when
  // an invalid fee transaction becomes a drop rather than a raise.
  private val fixingDataApplicationFeeValidationActivationOrdinal =
    fieldsAddedOrdinals.fixingDataApplicationFeeValidationFor(environment)

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
    historicalDependencyResolution: Boolean,
    parentSnapshotVersion: SnapshotVersion,
    allowSpendBlockAcceptanceMode: AllowSpendBlockAcceptanceMode
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
    preventingAllowSpendResurrection = fieldsAddedOrdinals.preventingAllowSpendResurrection
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

    validatedFeeTxs <- balanceOps.validateFeeTxs(
      feeTransactionsForAcceptance,
      isEnabled(
        maybeLastGlobalSyncView.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue),
        feeTransactionSecurityActivationOrdinal
      ),
      maybeLastGlobalSyncView.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue) >=
        fixingDataApplicationFeeValidationActivationOrdinal
    )

    // Gated on the GLOBAL ordinal, like every other FieldsAddedOrdinals entry -- the currency ordinal is
    // only used for balance adjustments. At or below the activation the original wrapping path runs, so
    // history replays to the state that was actually signed.
    (updatedBalancesByFeeTransactions, acceptedFeeTxs) <- balanceOps.acceptFeeTxs(
      updatedBalancesByRewards,
      validatedFeeTxs,
      maybeLastGlobalSyncView.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue) >
        fieldsAddedOrdinals.fixingFeeTransactionBalanceOverflow.getOrElse(environment, SnapshotOrdinal.MaxValue)
    )

    callerSharedArtifacts = sharedArtifactsForAcceptance
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
        unappliedGlobalChangeOrdinals = syncData.unappliedGlobalChangeOrdinals,
        snapshotProtocolV1ActivationOrdinal = currencySnapshotProtocolV1ActivationOrdinal
      )
    }
    // Recognition is globally authorized by the announced GL0 activation boundary, while
    // validateReset independently requires the reset's selected target to be at/after it.
    // Parent v1 keeps recognition enabled forever for a lineage even if a replayer's local
    // GL0 cursor is temporarily behind the activation boundary.
    resetRecognitionEnabled =
      CurrencySnapshotSemantics.usesDeterministicHistory(parentSnapshotVersion) ||
        CurrencySnapshotSemantics.isActivationAuthorized(
          lastUnsyncGlobalSnapshot.ordinal,
          currencySnapshotProtocolV1ActivationOrdinal
        )

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

    activationReference = maybeSnapshotOrdinalSync
      .orElse(maybeLastGlobalSyncView.map(_.ordinal))
      .getOrElse(SnapshotOrdinal.MinValue)
    transitionHistoryProven = lastUnsyncMetagraphSyncData
      .flatMap(_.get(metagraphId))
      .forall(syncData =>
        CurrencySnapshotSemantics.legacyHistoryResolvedThrough(
          syncData.unappliedGlobalChangeOrdinals,
          activationReference
        )
      )
    snapshotVersion = CurrencySnapshotSemantics.nextVersion(
      parentSnapshotVersion,
      activationReference,
      currencySnapshotProtocolV1ActivationOrdinal,
      transitionHistoryProven
    )
    deterministicHistoryActive = CurrencySnapshotSemantics.usesDeterministicHistory(snapshotVersion)
    _ <- new IllegalStateException("A recovery reset must activate deterministic Currency snapshot history")
      .raiseError[F, Unit]
      .whenA(globalSnapshotSyncAcceptanceResult.isRecoveryReset && !deterministicHistoryActive)
    transitionOutcome =
      if (CurrencySnapshotSemantics.usesDeterministicHistory(parentSnapshotVersion)) "deterministic"
      else if (deterministicHistoryActive) "activated"
      else if (
        CurrencySnapshotSemantics.isActivationAuthorized(
          activationReference,
          currencySnapshotProtocolV1ActivationOrdinal
        ) && !transitionHistoryProven
      ) "blocked_unproven"
      else "legacy"
    _ <- metrics.traverse_ { telemetry =>
      telemetry
        .incrementCounter(
          "dag_currency_l0_snapshot_protocol_total",
          Seq(Metrics.unsafeLabelName("outcome") -> transitionOutcome)
        )
        .attempt
        .void
    }
    // Signed CurrencySnapshot.version is the semantic boundary. Version 1.0.0 never
    // consults the archive/network callback, including during historical recreation.
    dependencyMode = GlobalSnapshotOpsManager.selectDependencyMode(historicalDependencyResolution, deterministicHistoryActive)
    // Legacy artifacts preserve rc.12 behavior byte-for-byte. Version 1.0.0 derives the
    // cumulative GlobalSnapshotsProcessed artifact only from its signed parent and GSI.
    acceptedSharedArtifacts =
      if (deterministicHistoryActive) callerSharedArtifacts.filterNot(_.isInstanceOf[GlobalSnapshotsProcessed])
      else callerSharedArtifacts

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
        lastGlobalSnapshotEpochProgress,
        allowSpendBlockAcceptanceMode.creditDestination
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
      deterministicProcessedHistory = deterministicHistoryActive
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
          metagraphIdSpendTransactions,
          // Replay against the global ordinal committed by the previous currency snapshot, never the live GL0 head.
          maybeLastGlobalSyncView.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue) > preventingAllowSpendResurrection
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
      // A metagraph may be authorized at several ordinals, so select the block matching this one
      // rather than assuming a single entry. Keying uniquely meant the last block in the resource
      // silently retired every earlier block for the same currency: replaying one of those ordinals
      // applied no adjustment and diverged without raising, and a follow-up adjustment could not be
      // scheduled at all.
      metagraphsBalancesAdjustments.get(lastSnapshotContext.address) match {
        case None =>
          if (balanceAdjustments.nonEmpty) {
            val unauthorizedError = new RuntimeException(
              s"Metagraph $metagraphId not authorized to perform balance updates on ordinal $snapshotOrdinal"
            )
            Async[F].raiseError(unauthorizedError)
          } else {
            updatedBalancesBySpendTransactions.pure[F]
          }
        case Some(infos) =>
          infos
            .find(info => info.snapshotOrdinal === snapshotOrdinal && info.environment === environment)
            .fold(updatedBalancesBySpendTransactions.pure[F]) { info =>
              info.balanceAdjustFunction(updatedBalancesBySpendTransactions, balanceAdjustments) match {
                case Right(balances) => balances.pure[F]
                case Left(error)     => Async[F].raiseError(new RuntimeException(s"Balance adjustment failed: $error"))
              }
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

    globalSnapshotProcessedEvents: SortedSet[SharedArtifact] =
      if (globalSnapshotsProcessed.nonEmpty)
        SortedSet[SharedArtifact](GlobalSnapshotsProcessed(globalSnapshotsProcessed))
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
      acceptedSharedArtifacts ++ allowSpendsExpiredEvents ++ tokenUnlocksEvents ++ globalSnapshotProcessedEvents,
      acceptedFeeTxs,
      csi,
      stateProof,
      globalSyncView,
      lastGlobalSnapshotOrdinal,
      snapshotOrdinalToCheckFields,
      snapshotVersion
    )

  def acceptRewardTxs(
    baseBalances: SortedMap[Address, Balance],
    newUpdatedBalance: Map[Address, Balance],
    rewards: SortedSet[RewardTransaction]
  ): F[(SortedMap[Address, Balance], SortedSet[RewardTransaction])] =
    balanceOps.acceptRewardTxs(baseBalances, newUpdatedBalance, rewards)
}
