package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Order
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.dag.l0.domain.snapshot.programs.UpdateNodeParametersCutter
import io.constellationnetwork.dag.l0.infrastructure.rewards.RewardsService
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.DelegatedRewardsConfigProvider
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.domain.event.EventCutter
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.consensus.{ConsensusLog, ControllerEvidenceDerivation}
import io.constellationnetwork.node.shared.infrastructure.delegatedStake.RewardsInfoStorage
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global.GlobalSnapshotAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.snapshot.{RewardsInput, _}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake.UpdateDelegatedStake
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.{Transaction, TransactionReference}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelValidationType}
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Core consensus functions for Global Snapshot creation and validation.
  *
  * Both the leader and every follower independently call `createProposalArtifact` from the same inputs (events, lastArtifact, context,
  * facilitators). If any step is non-deterministic, followers produce a different artifact hash than the leader, triggering the slower
  * validation path (which mutates MptStore twice) and potentially causing cascading state divergence.
  *
  * '''Determinism contract''': Given identical `(lastArtifact, context, events, facilitators)`, every peer MUST produce byte-identical
  * `(GlobalSnapshotArtifact, GlobalSnapshotContext)`. All collections passed to the acceptance pipeline must be in canonical order
  * (sorted).
  */
abstract class GlobalSnapshotConsensusFunctions[F[_]: Async: SecurityProvider]
    extends SnapshotConsensusFunctions[
      F,
      GlobalSnapshotEvent,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      ConsensusTrigger
    ] {}

object GlobalSnapshotConsensusFunctions {

  private[snapshot] def delegatedRewardRecipients(facilitators: Set[PeerId]): List[PeerId] =
    facilitators.toList.sorted

  private[snapshot] def usesFullCommitteeRewards(ordinal: SnapshotOrdinal, activation: SnapshotOrdinal): Boolean =
    ordinal >= activation

  def make[F[_]: Async: SecurityProvider: JsonSerializer: Metrics](
    globalSnapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[F],
    collateral: Amount,
    rewardsService: RewardsService[F],
    eventCutter: EventCutter[F, StateChannelEvent, DAGEvent],
    updateNodeParametersCutter: UpdateNodeParametersCutter[F],
    environment: AppEnvironment,
    delegatedRewardsConfigProvider: DelegatedRewardsConfigProvider,
    v3MigrationOrdinal: SnapshotOrdinal,
    setSumFixOrdinal: SnapshotOrdinal,
    delegatedRewardsFullCommitteeOrdinal: SnapshotOrdinal,
    incrementalDelegatedStakingStartingOrdinal: SnapshotOrdinal,
    mptStore: MptStore[F, GlobalStateKey],
    activeAdmissionPromoteThreshold: Int
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass[F](getClass)
    private val balanceEventStageLabel: Metrics.LabelName = Metrics.unsafeLabelName("stage")
    private val balanceEventTypeLabel: Metrics.LabelName = Metrics.unsafeLabelName("event_type")

    def getRequiredCollateral: Amount = collateral

    // Read from consensus-agreed context (deterministic), NOT from MptStore (local mutable state).
    // After an abandoned round the MptStore may contain partial mutations that differ across nodes,
    // causing facilitatorFilter to compute different eligibility sets → fork.
    def getBalance(context: GlobalSnapshotContext, address: Address): F[Balance] =
      context.balances.getOrElse(address, Balance.empty).pure[F]

    /** Validates a leader's proposed artifact by independently reconstructing it from the same inputs.
      *
      * Called by followers when their locally-built artifact hash differs from the leader's proposal. Re-derives the consensus trigger from
      * `artifact.epochProgress` (not the local trigger) to prevent trigger-divergence false mismatches. If the reconstructed artifact
      * equals the leader's, returns Right with the validated artifact and context; otherwise returns Left with the mismatch.
      *
      * '''Side effect''': Calls `createProposalArtifact` which mutates the shared MptStore. The caller must take a savepoint before calling
      * and restore on failure to prevent partial state leaking.
      */
    override def validateArtifact(
      lastSignedArtifact: Signed[GlobalSnapshotArtifact],
      lastContext: GlobalSnapshotContext,
      trigger: ConsensusTrigger,
      artifact: GlobalSnapshotArtifact,
      facilitators: Set[PeerId],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      peerHistory: Option[ConsensusOperationalState] = None
    )(implicit hasher: Hasher[F]): F[Either[InvalidArtifact, (GlobalSnapshotArtifact, GlobalSnapshotContext)]] = {
      val dagEvents = artifact.blocks.unsorted.map(_.block).map(DAGEvent(_))
      val scEvents = artifact.stateChannelSnapshots.toList.flatMap {
        case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _)).map(StateChannelEvent(_)).toList
      }
      val allowSpendEvents = artifact.allowSpendBlocks.map(_.toList.map(AllowSpendEvent(_))).getOrElse(List.empty)
      val tokenLockEvents = artifact.tokenLockBlocks.map(_.toList.map(TokenLockEvent(_))).getOrElse(List.empty)
      val unpEvents =
        artifact.updateNodeParameters.getOrElse(SortedMap.empty[Id, Signed[UpdateNodeParameters]]).values.map(UpdateNodeParametersEvent(_))
      val cdsEvents = artifact.activeDelegatedStakes
        .getOrElse(SortedMap.empty[Address, List[Signed[UpdateDelegatedStake.Create]]])
        .values
        .flatMap(_.map(CreateDelegatedStakeEvent(_)))
      val wdsEvents = artifact.delegatedStakesWithdrawals
        .getOrElse(SortedMap.empty[Address, List[Signed[UpdateDelegatedStake.Withdraw]]])
        .values
        .flatMap(_.map(WithdrawDelegatedStakeEvent(_)))
      val cncEvents = artifact.activeNodeCollaterals
        .getOrElse(SortedMap.empty[Address, List[Signed[UpdateNodeCollateral.Create]]])
        .values
        .flatMap(_.map(CreateNodeCollateralEvent(_)))
      val wncEvents = artifact.nodeCollateralWithdrawals
        .getOrElse(SortedMap.empty[Address, List[Signed[UpdateNodeCollateral.Withdraw]]])
        .values
        .flatMap(_.map(WithdrawNodeCollateralEvent(_)))

      val events: Set[GlobalSnapshotEvent] =
        dagEvents ++ scEvents ++ allowSpendEvents ++ unpEvents ++ tokenLockEvents ++ cdsEvents ++ wdsEvents ++ cncEvents ++ wncEvents

      // Derive the consensus trigger from the artifact itself rather than trusting the local
      // consensus trigger, which may differ across nodes (e.g. a node observing EventTrigger
      // while the leader used TimeTrigger). An incremented epochProgress unambiguously means
      // TimeTrigger was used; otherwise it was EventTrigger.
      val artifactTrigger: ConsensusTrigger =
        if (artifact.epochProgress.value.value > lastSignedArtifact.epochProgress.value.value)
          TimeTrigger
        else
          EventTrigger

      def usingJson = createProposalArtifact(
        lastSignedArtifact.ordinal,
        lastSignedArtifact,
        lastContext,
        Hasher.forJson[F],
        artifactTrigger,
        events,
        facilitators,
        getGlobalSnapshotByOrdinal,
        peerHistory
      )

      def check(result: F[(GlobalSnapshotArtifact, GlobalSnapshotContext, Set[GlobalSnapshotEvent])]) =
        result.map {
          case (recreatedArtifact, context, _) =>
            if (recreatedArtifact === artifact)
              (artifact, context).asRight[InvalidArtifact]
            else
              GlobalArtifactMismatch(artifact, recreatedArtifact).asLeft[(GlobalSnapshotArtifact, GlobalSnapshotContext)]
        }

      check(usingJson)
    }

    /** Builds a new GlobalIncrementalSnapshot proposal from the previous snapshot and pending events.
      *
      * '''Determinism''': This method MUST produce byte-identical output on every peer given the same inputs. All event lists extracted
      * from the unordered `events: Set[GlobalSnapshotEvent]` are sorted before being passed to the acceptance pipeline to guarantee
      * canonical processing order.
      *
      * The pipeline:
      *   1. Extract and sort events by type (DAG blocks, state channels, allow spends, token locks, etc.) 2. Cut events to fit within
      *      bounds (eventCutter) 3. Derive facilitators from the consensus facility declarations (not from proof signatures) 4. Pass sorted
      *      event lists to `GlobalSnapshotAcceptanceManager.accept()` 5. Build the `GlobalIncrementalSnapshot` artifact with all accepted
      *      data
      *
      * @param events
      *   Unordered set of events — iteration order is non-deterministic. Events are sorted after extraction to ensure deterministic
      *   acceptance.
      * @param facilitators
      *   Current round's facilitators (deterministic: all nodes must receive all facility declarations before advancing from
      *   CollectingFacilities).
      */
    def createProposalArtifact(
      lastKey: GlobalSnapshotKey,
      lastArtifact: Signed[GlobalSnapshotArtifact],
      snapshotContext: GlobalSnapshotContext,
      lastArtifactHasher: Hasher[F],
      trigger: ConsensusTrigger,
      events: Set[GlobalSnapshotEvent],
      facilitators: Set[PeerId],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      peerHistory: Option[ConsensusOperationalState] = None
    )(implicit hasher: Hasher[F]): F[(GlobalSnapshotArtifact, GlobalSnapshotContext, Set[GlobalSnapshotEvent])] = {
      val scEventsBeforeCut = events.collect { case sc: StateChannelEvent => sc }
      val dagEventsBeforeCut = events.collect { case d: DAGEvent => d }
      val allowSpendEventsForAcceptance = events.collect { case as: AllowSpendEvent => as }
      val tokenLockEventsForAcceptance = events.collect { case as: TokenLockEvent => as }
      val unpEventsBeforeCut = events.collect { case unp: UpdateNodeParametersEvent => unp }

      val cdsEventsForAcceptance = events.collect { case e: CreateDelegatedStakeEvent => e }
      val wdsEventsForAcceptance = events.collect { case e: WithdrawDelegatedStakeEvent => e }
      val cncEventsForAcceptance = events.collect { case e: CreateNodeCollateralEvent => e }
      val wncEventsForAcceptance = events.collect { case e: WithdrawNodeCollateralEvent => e }

      val dagEvents = dagEventsBeforeCut.filter(_.value.height > lastArtifact.height)

      val delegatedConfig = delegatedRewardsConfigProvider.getConfig()

      def shouldUseDelegatedRewards(currentOrdinal: SnapshotOrdinal, currentEpochProgress: EpochProgress): Boolean = {
        val asOfEpoch = delegatedConfig.emissionConfig
          .get(environment)
          .map(f => f(currentEpochProgress))
          .map(_.asOfEpoch)
          .getOrElse(EpochProgress.MaxValue)
        currentOrdinal.value >= v3MigrationOrdinal.value &&
        currentEpochProgress.value.value >= asOfEpoch.value.value
      }

      val classicRewardsFn: (
        Signed[GlobalSnapshotArtifact],
        SortedMap[Address, Balance],
        SortedSet[Signed[Transaction]],
        ConsensusTrigger,
        Set[GlobalSnapshotEvent],
        Option[DataCalculatedState]
      ) => F[DelegatedRewardsResult] = { (signedArtifact, balances, txs, trigger, events, calcState) =>
        rewardsService.classicRewards
          .distribute(
            signedArtifact,
            balances,
            txs,
            trigger,
            events,
            calcState
          )
          .map { rewardTxs =>
            if (signedArtifact.ordinal.value < setSumFixOrdinal.value) {
              DelegatedRewardsResult(
                delegatorRewardsMap = SortedMap.empty,
                updatedCreateDelegatedStakes = SortedMap.empty,
                updatedWithdrawDelegatedStakes = SortedMap.empty,
                nodeOperatorRewards = rewardTxs,
                reservedAddressRewards = SortedSet.empty,
                withdrawalRewardTxs = SortedSet.empty,
                totalEmittedRewardsAmount =
                  Amount(NonNegLong.unsafeFrom(rewardTxs.toList.map(_.amount.value.value).distinct.sum)) // mimic incorrect behaviour
              )
            } else {
              DelegatedRewardsResult(
                delegatorRewardsMap = SortedMap.empty,
                updatedCreateDelegatedStakes = SortedMap.empty,
                updatedWithdrawDelegatedStakes = SortedMap.empty,
                nodeOperatorRewards = rewardTxs,
                reservedAddressRewards = SortedSet.empty,
                withdrawalRewardTxs = SortedSet.empty,
                totalEmittedRewardsAmount = Amount(NonNegLong.unsafeFrom(rewardTxs.toList.map(_.amount.value.value).sum))
              )
            }
          }
      }

      val rewardsWithFacilitators: List[(Address, PeerId)] => RewardsInput => F[DelegatedRewardsResult] = {
        faciltators: List[(Address, PeerId)] =>
          {
            case ClassicRewardsInput(txs) =>
              classicRewardsFn(lastArtifact, snapshotContext.balances, txs, trigger, events, None)

            case DelegateRewardsInput(udsar, psu, ep) =>
              val ordinal = lastArtifact.ordinal.next
              if (shouldUseDelegatedRewards(ordinal, ep)) {
                rewardsService.delegatedRewards.distribute(snapshotContext, trigger, ep, faciltators, udsar, psu).map {
                  delegatedRewardsResult =>
                    if (ordinal > incrementalDelegatedStakingStartingOrdinal) {
                      val updatedCreateDelegatedStakes = delegatedRewardsResult.updatedCreateDelegatedStakes.view.mapValues { records =>
                        records.map { r =>
                          r.copy(
                            currentTokenLockRef = r.currentTokenLockRef.orElse(r.tokenLockRef.some),
                            currentAmount = r.currentAmount.orElse(r.amount.some)
                          )
                        }
                      }.to(SortedMap)

                      delegatedRewardsResult.copy(updatedCreateDelegatedStakes = updatedCreateDelegatedStakes)
                    } else {
                      delegatedRewardsResult
                    }
                }
              } else {
                classicRewardsFn(lastArtifact, snapshotContext.balances, SortedSet.empty, trigger, events, None)
              }
          }
      }

      def getLastArtifactHash = lastArtifactHasher.getLogic(lastArtifact.value.ordinal) match {
        case JsonHash => lastArtifactHasher.hash(lastArtifact.value)
        case KryoHash => lastArtifactHasher.hash(GlobalIncrementalSnapshotV1.fromGlobalIncrementalSnapshot(lastArtifact.value))
      }

      def balanceEventMetric(stage: String, eventType: String, count: Long): F[Unit] = {
        val tags = Seq(
          balanceEventStageLabel -> stage,
          balanceEventTypeLabel -> eventType
        )

        Metrics[F].updateGauge("dag_global_snapshot_balance_event_count", count.toLong, tags) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_balance_event_total", count, tags)
      }

      def emitBalanceEventMetrics(stage: String, counts: (String, Long)*): F[Unit] =
        counts.toList.traverse_ { case (eventType, count) => balanceEventMetric(stage, eventType, count) }

      def dagProposalMetric(stage: String, blockCount: Long, txCount: Long): F[Unit] = {
        val tags = Seq(balanceEventStageLabel -> stage)

        Metrics[F].updateGauge("dag_global_snapshot_dag_tx_proposal_block_count", blockCount, tags) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_dag_tx_proposal_block_total", blockCount, tags) >>
          Metrics[F].updateGauge("dag_global_snapshot_dag_tx_proposal_tx_count", txCount, tags) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_dag_tx_proposal_tx_total", txCount, tags)
      }

      final case class BalanceEventDiagnostics(
        accepted: List[(String, Long)],
        rejected: List[(String, Long)],
        logFields: List[(String, String)]
      )

      final case class DagParentFilterResult(
        accepted: List[Signed[Block]],
        held: List[DAGEvent],
        maxGap: Long
      )

      def filterDagBlocksWithMissingParents(blocks: List[Signed[Block]]): F[DagParentFilterResult] = {
        def chainBySource(block: Signed[Block]): List[(Address, List[Signed[Transaction]])] =
          block.value.transactions.toNonEmptyList
            .groupBy(_.value.source)
            .toList
            .map { case (address, txs) => address -> txs.sortBy(_.ordinal).toList }

        def evaluateBlock(
          projectedRefs: SortedMap[Address, TransactionReference],
          block: Signed[Block]
        ): F[(Boolean, Long, SortedMap[Address, TransactionReference])] =
          chainBySource(block).foldLeftM((false, 0L, projectedRefs)) {
            case ((awaitingParent, maxGap, refs), (address, txChain)) =>
              val lastTxRef = refs.getOrElse(address, TransactionReference.empty)
              val head = txChain.head
              val parentOrdinal = head.value.parent.ordinal.value.value
              val lastOrdinal = lastTxRef.ordinal.value.value
              val gap = math.max(0L, parentOrdinal - lastOrdinal)

              if (parentOrdinal > lastOrdinal)
                (true, math.max(maxGap, gap), refs).pure[F]
              else if (parentOrdinal === lastOrdinal && head.value.parent.hash === lastTxRef.hash)
                TransactionReference.of(txChain.last).map { lastRef =>
                  (awaitingParent, maxGap, refs.updated(address, lastRef))
                }
              else
                (awaitingParent, maxGap, refs).pure[F]
          }

        blocks.sorted
          .foldLeftM((List.empty[Signed[Block]], List.empty[DAGEvent], 0L, snapshotContext.lastTxRefs)) {
            case ((accepted, held, maxGap, projectedRefs), block) =>
              evaluateBlock(projectedRefs, block).map {
                case (true, blockMaxGap, _) =>
                  (accepted, DAGEvent(block) :: held, math.max(maxGap, blockMaxGap), projectedRefs)
                case (false, blockMaxGap, updatedRefs) =>
                  (block :: accepted, held, math.max(maxGap, blockMaxGap), updatedRefs)
              }
          }
          .map {
            case (accepted, held, maxGap, _) =>
              DagParentFilterResult(accepted.reverse, held.reverse, maxGap)
          }
      }

      for {
        lastArtifactHash <- getLastArtifactHash
        currentOrdinal = lastArtifact.ordinal.next
        currentEpochProgress = trigger match {
          case EventTrigger => lastArtifact.epochProgress
          case TimeTrigger  => lastArtifact.epochProgress.next
        }

        (scEvents, rawBlocksForAcceptance) <- eventCutter.cut(
          scEventsBeforeCut.toList.sortBy(_.value.address),
          dagEvents.toList,
          snapshotContext,
          currentOrdinal
        )
        dagParentFilter <- filterDagBlocksWithMissingParents(rawBlocksForAcceptance.map(_.value))
        blocksForAcceptance = dagParentFilter.accepted
        heldDagEvents = dagParentFilter.held.toSet
        rawDagTxForAcceptanceCount = rawBlocksForAcceptance.map(_.value.value).map(_.transactions.size.toLong).sum
        heldDagTxCount = dagParentFilter.held.map(_.value.value.transactions.size.toLong).sum

        unpEventsForAcceptance <- updateNodeParametersCutter.cut(unpEventsBeforeCut.toList, snapshotContext, currentOrdinal)

        lastActiveTips <- lastArtifact.activeTips(Async[F], lastArtifactHasher)
        lastDeprecatedTips = lastArtifact.tips.deprecated

        // Derive lastFacilitators from the frozen round-start set rather than
        // lastArtifact.proofs. Different nodes can collect different proof subsets for the same
        // artifact, whereas the StateAdvancer passes `state.roundStartFacilitators`, which is
        // never narrowed by node-local mid-round withdrawals.
        // Below the correction gate, preserve the briefly-deployed evidence-score filter so
        // historical snapshots replay byte-identically. At/after the gate, delegated rewards
        // follow every member of the frozen signing committee; admission score affects Core and
        // leader classification, not Tier-1 lease retention or payout eligibility.
        rewardPeerIds =
          if (usesFullCommitteeRewards(currentOrdinal, delegatedRewardsFullCommitteeOrdinal))
            delegatedRewardRecipients(facilitators)
          else
            ControllerEvidenceDerivation
              .legacyRewardQualifiedFacilitators(
                SortedSet.from(facilitators),
                peerHistory.flatMap(_.controllerEvidence),
                activeAdmissionPromoteThreshold
              )
              .toList
        lastFacilitators <- rewardPeerIds.traverse { peerId =>
          PeerId._Id.get(peerId).toAddress.map(_ -> peerId)
        }
        // Sort all event lists before passing to accept() to ensure deterministic ordering.
        // Events are extracted from Set[GlobalSnapshotEvent] (line 114) which has non-deterministic
        // iteration order. Without sorting, different nodes may process events in different orders,
        // causing divergent acceptance results (e.g. first-wins duplicate logic in delegated stakes).
        // All types derive Order, and Signed[T] provides Order when T has Order, ensuring
        // canonical ordering across all peers. Using .sorted (Order-based) instead of
        // .sortBy(_.show) (String-based) avoids collisions when distinct events have identical
        // Show representations.
        sortedAllowSpendEvents = allowSpendEventsForAcceptance.toList.map(_.value).sorted
        sortedTokenLockEvents = tokenLockEventsForAcceptance.toList.map(_.value).sorted
        sortedCdsEvents = cdsEventsForAcceptance.toList.map(_.value).sorted(Signed.ordering(Order[UpdateDelegatedStake.Create].toOrdering))
        sortedWdsEvents = wdsEventsForAcceptance.toList
          .map(_.value)
          .sorted(Signed.ordering(Order[UpdateDelegatedStake.Withdraw].toOrdering))
        sortedCncEvents = cncEventsForAcceptance.toList.map(_.value).sorted(Signed.ordering(Order[UpdateNodeCollateral.Create].toOrdering))
        sortedWncEvents = wncEventsForAcceptance.toList
          .map(_.value)
          .sorted(Signed.ordering(Order[UpdateNodeCollateral.Withdraw].toOrdering))
        dagTxForAcceptanceCount = blocksForAcceptance.map(_.value).map(_.transactions.size.toLong).sum
        allowSpendTxForAcceptanceCount = sortedAllowSpendEvents.map(_.transactions.size.toLong).sum
        tokenLockTxForAcceptanceCount = sortedTokenLockEvents.map(_.tokenLocks.size.toLong).sum
        dagTxAvailableBeforeCutCount = dagEventsBeforeCut.toList.map(_.value.value.transactions.size.toLong).sum

        _ <- emitBalanceEventMetrics(
          "input",
          "dag_block" -> dagEventsBeforeCut.size.toLong,
          "state_channel" -> scEventsBeforeCut.size.toLong,
          "allow_spend_block" -> allowSpendEventsForAcceptance.size.toLong,
          "token_lock_block" -> tokenLockEventsForAcceptance.size.toLong,
          "delegated_stake_create" -> cdsEventsForAcceptance.size.toLong,
          "delegated_stake_withdraw" -> wdsEventsForAcceptance.size.toLong,
          "node_collateral_create" -> cncEventsForAcceptance.size.toLong,
          "node_collateral_withdraw" -> wncEventsForAcceptance.size.toLong,
          "update_node_parameters" -> unpEventsBeforeCut.size.toLong
        )
        _ <- emitBalanceEventMetrics(
          "cut",
          "dag_block" -> rawBlocksForAcceptance.size.toLong,
          "dag_block_admitted" -> blocksForAcceptance.size.toLong,
          "dag_block_held_missing_parent" -> dagParentFilter.held.size.toLong,
          "dag_spend" -> rawDagTxForAcceptanceCount,
          "dag_spend_admitted" -> dagTxForAcceptanceCount,
          "dag_spend_held_missing_parent" -> heldDagTxCount,
          "state_channel" -> scEvents.size.toLong,
          "allow_spend_block" -> sortedAllowSpendEvents.size.toLong,
          "allow_spend" -> allowSpendTxForAcceptanceCount,
          "token_lock_block" -> sortedTokenLockEvents.size.toLong,
          "token_lock" -> tokenLockTxForAcceptanceCount,
          "delegated_stake_create" -> sortedCdsEvents.size.toLong,
          "delegated_stake_withdraw" -> sortedWdsEvents.size.toLong,
          "node_collateral_create" -> sortedCncEvents.size.toLong,
          "node_collateral_withdraw" -> sortedWncEvents.size.toLong,
          "update_node_parameters" -> unpEventsForAcceptance.size.toLong
        )
        _ <- dagProposalMetric("available_before_cut", dagEventsBeforeCut.size.toLong, dagTxAvailableBeforeCutCount)
        _ <- dagProposalMetric("cut", rawBlocksForAcceptance.size.toLong, rawDagTxForAcceptanceCount)
        _ <- dagProposalMetric("included_after_parent_filter", blocksForAcceptance.size.toLong, dagTxForAcceptanceCount)
        _ <- dagProposalMetric("held_after_parent_filter", dagParentFilter.held.size.toLong, heldDagTxCount)
        parentFilterDecisionLabel = Metrics.unsafeLabelName("decision")
        parentFilterReasonLabel = Metrics.unsafeLabelName("reason")
        _ <- Metrics[F].incrementCounterBy(
          "dag_global_snapshot_dag_tx_parent_filter_total",
          blocksForAcceptance.size,
          Seq(parentFilterDecisionLabel -> "admitted", parentFilterReasonLabel -> "none")
        )
        _ <- Metrics[F].incrementCounterBy(
          "dag_global_snapshot_dag_tx_parent_filter_total",
          dagParentFilter.held.size,
          Seq(parentFilterDecisionLabel -> "held", parentFilterReasonLabel -> "parent_ordinal_above_last")
        )
        _ <- Metrics[F].incrementCounterBy(
          "dag_global_snapshot_dag_tx_parent_ordinal_above_last_total",
          dagParentFilter.held.size,
          Seq(parentFilterReasonLabel -> "proposal_filter")
        )
        _ <- Metrics[F].updateGauge("dag_global_snapshot_dag_tx_awaiting_parent_backlog", dagParentFilter.held.size.toLong)
        _ <- Metrics[F].updateGauge("dag_global_snapshot_dag_tx_awaiting_parent_max_gap", dagParentFilter.maxGap)

        _ <- ConsensusLog.info(
          logger,
          Category.Proposal,
          currentOrdinal.show,
          "n/a",
          Event.ProposalEvents,
          "trigger" -> trigger.toString,
          "events.total" -> events.size.toString,
          "dag" -> blocksForAcceptance.size.toString,
          "dagHeldMissingParent" -> dagParentFilter.held.size.toString,
          "dagTx" -> dagTxForAcceptanceCount.toString,
          "dagTxHeldMissingParent" -> heldDagTxCount.toString,
          "dagTxMissingParentMaxGap" -> dagParentFilter.maxGap.toString,
          "sc" -> scEvents.size.toString,
          "allowSpend" -> sortedAllowSpendEvents.size.toString,
          "allowSpendTx" -> allowSpendTxForAcceptanceCount.toString,
          "tokenLock" -> sortedTokenLockEvents.size.toString,
          "tokenLockTx" -> tokenLockTxForAcceptanceCount.toString,
          "unp" -> unpEventsForAcceptance.size.toString,
          "delegStakeCreate" -> sortedCdsEvents.size.toString,
          "delegStakeWithdraw" -> sortedWdsEvents.size.toString,
          "nodeCollCreate" -> sortedCncEvents.size.toString,
          "nodeCollWithdraw" -> sortedWncEvents.size.toString
        )

        acceptStartMs <- Async[F].monotonic.map(_.toMillis)
        (
          acceptanceResult,
          allowSpendBlockAcceptanceResult,
          tokenLockBlockAcceptanceResult,
          delegatedStakeAcceptanceResult,
          nodeCollateralAcceptanceResult,
          scSnapshots,
          returnedSCEvents,
          acceptedRewardTxs,
          snapshotInfo,
          stateProof,
          spendActions,
          updateNodeParameters,
          sharedArtifacts,
          delegatorRewardsMap
        ) <-
          globalSnapshotAcceptanceManager
            .accept(
              currentOrdinal,
              currentEpochProgress,
              blocksForAcceptance,
              sortedAllowSpendEvents,
              sortedTokenLockEvents,
              scEvents.map(_.value),
              unpEventsForAcceptance.map(_.updateNodeParameters),
              sortedCdsEvents,
              sortedWdsEvents,
              sortedCncEvents,
              sortedWncEvents,
              snapshotContext,
              lastActiveTips,
              lastDeprecatedTips,
              rewardsWithFacilitators(lastFacilitators),
              StateChannelValidationType.Full,
              getGlobalSnapshotByOrdinal
            )
        acceptEndMs <- Async[F].monotonic.map(_.toMillis)
        balanceDiagnostics = {
          val blocksRejectedCount = acceptanceResult.notAccepted.size.toLong
          val dagTxAcceptedCount = acceptanceResult.accepted.toList.map { case (block, _) => block.transactions.size.toLong }.sum
          val dagTxRejectedCount = acceptanceResult.notAccepted.toList.map { case (block, _) => block.transactions.size.toLong }.sum
          val dagParentOrdinalAboveLastCount = acceptanceResult.notAccepted.count {
            case (_, AwaitingTransaction(_, ParentOrdinalAboveLastTxOrdinal(_, _))) => true
            case _                                                                  => false
          }.toLong
          val dagParentOrdinalAboveLastMaxGap = acceptanceResult.notAccepted.collect {
            case (_, AwaitingTransaction(_, ParentOrdinalAboveLastTxOrdinal(parentOrdinal, lastTxOrdinal))) =>
              math.max(0L, parentOrdinal.value.value - lastTxOrdinal.value.value)
          }.maxOption.getOrElse(0L)
          val allowSpendAcceptedBlockCount = allowSpendBlockAcceptanceResult.accepted.size.toLong
          val allowSpendAcceptedTxCount = allowSpendBlockAcceptanceResult.accepted.toList.map(_.transactions.size.toLong).sum
          val allowSpendRejectedBlockCount = allowSpendBlockAcceptanceResult.notAccepted.size.toLong
          val allowSpendRejectedTxCount = allowSpendBlockAcceptanceResult.notAccepted.toList.map {
            case (block, _) => block.transactions.size.toLong
          }.sum
          val tokenLockAcceptedBlockCount = tokenLockBlockAcceptanceResult.accepted.size.toLong
          val tokenLockAcceptedTxCount = tokenLockBlockAcceptanceResult.accepted.toList.map(_.tokenLocks.size.toLong).sum
          val tokenLockRejectedBlockCount = tokenLockBlockAcceptanceResult.notAccepted.size.toLong
          val tokenLockRejectedTxCount = tokenLockBlockAcceptanceResult.notAccepted.toList.map {
            case (block, _) => block.tokenLocks.size.toLong
          }.sum
          val delegatedStakeCreateAcceptedCount = delegatedStakeAcceptanceResult.acceptedCreates.values.map(_.size.toLong).sum
          val delegatedStakeCreateRejectedCount = delegatedStakeAcceptanceResult.notAcceptedCreates.size.toLong
          val delegatedStakeWithdrawAcceptedCount = delegatedStakeAcceptanceResult.acceptedWithdrawals.values.map(_.size.toLong).sum
          val delegatedStakeWithdrawRejectedCount = delegatedStakeAcceptanceResult.notAcceptedWithdrawals.size.toLong
          val nodeCollateralCreateAcceptedCount = nodeCollateralAcceptanceResult.acceptedCreates.values.map(_.size.toLong).sum
          val nodeCollateralCreateRejectedCount = nodeCollateralAcceptanceResult.notAcceptedCreates.size.toLong
          val nodeCollateralWithdrawAcceptedCount = nodeCollateralAcceptanceResult.acceptedWithdrawals.values.map(_.size.toLong).sum
          val nodeCollateralWithdrawRejectedCount = nodeCollateralAcceptanceResult.notAcceptedWithdrawals.size.toLong
          val stateChannelAcceptedCount = scSnapshots.values.map(_.size.toLong).sum
          val stateChannelRejectedCount = returnedSCEvents.size.toLong
          val spendActionsCount = spendActions.values.map(_.size.toLong).sum
          val updateNodeParametersCount = updateNodeParameters.size.toLong
          val sharedArtifactsCount = sharedArtifacts.size.toLong
          val delegateRewardsCount = delegatorRewardsMap.values.map(_.size.toLong).sum

          BalanceEventDiagnostics(
            accepted = List(
              "dag_block" -> acceptanceResult.accepted.size.toLong,
              "dag_spend" -> dagTxAcceptedCount,
              "state_channel" -> stateChannelAcceptedCount,
              "allow_spend_block" -> allowSpendAcceptedBlockCount,
              "allow_spend" -> allowSpendAcceptedTxCount,
              "token_lock_block" -> tokenLockAcceptedBlockCount,
              "token_lock" -> tokenLockAcceptedTxCount,
              "delegated_stake_create" -> delegatedStakeCreateAcceptedCount,
              "delegated_stake_withdraw" -> delegatedStakeWithdrawAcceptedCount,
              "node_collateral_create" -> nodeCollateralCreateAcceptedCount,
              "node_collateral_withdraw" -> nodeCollateralWithdrawAcceptedCount,
              "spend_action" -> spendActionsCount,
              "update_node_parameters" -> updateNodeParametersCount,
              "artifact" -> sharedArtifactsCount,
              "reward" -> acceptedRewardTxs.size.toLong,
              "delegate_reward" -> delegateRewardsCount
            ),
            rejected = List(
              "dag_block" -> blocksRejectedCount,
              "dag_block_parent_ordinal_above_last" -> dagParentOrdinalAboveLastCount,
              "dag_spend" -> dagTxRejectedCount,
              "state_channel" -> stateChannelRejectedCount,
              "allow_spend_block" -> allowSpendRejectedBlockCount,
              "allow_spend" -> allowSpendRejectedTxCount,
              "token_lock_block" -> tokenLockRejectedBlockCount,
              "token_lock" -> tokenLockRejectedTxCount,
              "delegated_stake_create" -> delegatedStakeCreateRejectedCount,
              "delegated_stake_withdraw" -> delegatedStakeWithdrawRejectedCount,
              "node_collateral_create" -> nodeCollateralCreateRejectedCount,
              "node_collateral_withdraw" -> nodeCollateralWithdrawRejectedCount
            ),
            logFields = List(
              "tx.accepted" -> dagTxAcceptedCount.toString,
              "tx.rejected" -> dagTxRejectedCount.toString,
              "tx.parentOrdinalAboveLast" -> dagParentOrdinalAboveLastCount.toString,
              "tx.parentOrdinalAboveLastMaxGap" -> dagParentOrdinalAboveLastMaxGap.toString,
              "allowSpendBlocks.accepted" -> allowSpendAcceptedBlockCount.toString,
              "allowSpendBlocks.rejected" -> allowSpendRejectedBlockCount.toString,
              "allowSpend.accepted" -> allowSpendAcceptedTxCount.toString,
              "allowSpend.rejected" -> allowSpendRejectedTxCount.toString,
              "tokenLockBlocks.accepted" -> tokenLockAcceptedBlockCount.toString,
              "tokenLockBlocks.rejected" -> tokenLockRejectedBlockCount.toString,
              "tokenLock.accepted" -> tokenLockAcceptedTxCount.toString,
              "tokenLock.rejected" -> tokenLockRejectedTxCount.toString,
              "delegStakeCreate.accepted" -> delegatedStakeCreateAcceptedCount.toString,
              "delegStakeCreate.rejected" -> delegatedStakeCreateRejectedCount.toString,
              "delegStakeWithdraw.accepted" -> delegatedStakeWithdrawAcceptedCount.toString,
              "delegStakeWithdraw.rejected" -> delegatedStakeWithdrawRejectedCount.toString,
              "nodeCollCreate.accepted" -> nodeCollateralCreateAcceptedCount.toString,
              "nodeCollCreate.rejected" -> nodeCollateralCreateRejectedCount.toString,
              "nodeCollWithdraw.accepted" -> nodeCollateralWithdrawAcceptedCount.toString,
              "nodeCollWithdraw.rejected" -> nodeCollateralWithdrawRejectedCount.toString,
              "spendActions" -> spendActionsCount.toString,
              "scSnapshots" -> stateChannelAcceptedCount.toString,
              "scReturned" -> stateChannelRejectedCount.toString,
              "rewards" -> acceptedRewardTxs.size.toString,
              "delegateRewards" -> delegateRewardsCount.toString
            )
          )
        }

        _ <- emitBalanceEventMetrics("accepted", balanceDiagnostics.accepted: _*)
        _ <- emitBalanceEventMetrics("rejected", balanceDiagnostics.rejected: _*)
        // State-size gauges: balances and lastTxRefs are the two dominant Address-keyed maps in
        // GlobalSnapshotInfo (each ~444k entries on the dust-bloated testnet), so they track the
        // on-disk/in-memory state magnitude and make the dust-sweep deflation observable as a step
        // drop at the sweep ordinal. snapshotInfo here is the post-sweep GSI returned by accept.
        _ <- Metrics[F].updateGauge("dag_global_snapshot_state_balances_count", snapshotInfo.balances.size.toLong)
        _ <- Metrics[F].updateGauge("dag_global_snapshot_state_last_tx_refs_count", snapshotInfo.lastTxRefs.size.toLong)
        _ <- Metrics[F].incrementCounterBy(
          "dag_global_snapshot_dag_tx_parent_ordinal_above_last_total",
          balanceDiagnostics.rejected.toMap.getOrElse("dag_block_parent_ordinal_above_last", 0L),
          Seq(parentFilterReasonLabel -> "acceptance")
        )
        _ <- ConsensusLog.info(
          logger,
          Category.Proposal,
          currentOrdinal.show,
          "n/a",
          Event.AcceptTiming,
          "acceptDurationMs" -> (acceptEndMs - acceptStartMs).toString
        )
        _ <- ConsensusLog.info(
          logger,
          Category.Proposal,
          currentOrdinal.show,
          "n/a",
          Event.AcceptanceResults,
          ("blocks.accepted" -> acceptanceResult.accepted.size.toString) ::
            ("blocks.notAccepted" -> acceptanceResult.notAccepted.size.toString) ::
            balanceDiagnostics.logFields: _*
        )

        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )

        (height, subHeight) <- getHeightAndSubHeight(lastArtifact, deprecated, remainedActive, accepted)

        returnedDAGEvents = getReturnedDAGEvents(acceptanceResult)

        acceptedDelegatedStakeCreates = delegatedStakeAcceptanceResult.acceptedCreates.view.mapValues(_.map(_._1)).toSortedMap
        acceptedDelegatedStakeWithdrawals = delegatedStakeAcceptanceResult.acceptedWithdrawals.view.mapValues(_.map(_._1)).toSortedMap
        acceptedNnodeCollateralCreates = nodeCollateralAcceptanceResult.acceptedCreates.view.mapValues(_.map(_._1)).toSortedMap
        acceptedNnodeCollateralWithdrawals = nodeCollateralAcceptanceResult.acceptedWithdrawals.view.mapValues(_.map(_._1)).toSortedMap

        globalSnapshot = GlobalIncrementalSnapshot(
          currentOrdinal,
          height,
          subHeight,
          lastArtifactHash,
          accepted,
          scSnapshots,
          acceptedRewardTxs,
          delegatorRewardsMap.some,
          currentEpochProgress,
          GlobalSnapshot.nextFacilitators,
          SnapshotTips(
            deprecated = deprecated,
            remainedActive = remainedActive
          ),
          stateProof,
          SortedSet.from(allowSpendBlockAcceptanceResult.accepted).some,
          SortedSet.from(tokenLockBlockAcceptanceResult.accepted).some,
          SortedMap.from(spendActions).some,
          updateNodeParameters.some,
          sharedArtifacts.some,
          acceptedDelegatedStakeCreates.some,
          acceptedDelegatedStakeWithdrawals.some,
          acceptedNnodeCollateralCreates.some,
          acceptedNnodeCollateralWithdrawals.some,
          peerHistory
        )
        _ <- emitBalanceEventMetrics(
          "artifact",
          "dag_block" -> globalSnapshot.blocks.size.toLong,
          "dag_spend" -> globalSnapshot.blocks.toList.map(_.block.transactions.size.toLong).sum,
          "state_channel" -> globalSnapshot.stateChannelSnapshots.values.map(_.size.toLong).sum,
          "allow_spend_block" -> globalSnapshot.allowSpendBlocks.fold(0L)(_.size.toLong),
          "allow_spend" -> globalSnapshot.allowSpendBlocks.fold(0L)(_.toList.map(_.transactions.size.toLong).sum),
          "token_lock_block" -> globalSnapshot.tokenLockBlocks.fold(0L)(_.size.toLong),
          "token_lock" -> globalSnapshot.tokenLockBlocks.fold(0L)(_.toList.map(_.tokenLocks.size.toLong).sum),
          "delegated_stake_create" -> globalSnapshot.activeDelegatedStakes.fold(0L)(_.values.map(_.size.toLong).sum),
          "delegated_stake_withdraw" -> globalSnapshot.delegatedStakesWithdrawals.fold(0L)(_.values.map(_.size.toLong).sum),
          "node_collateral_create" -> globalSnapshot.activeNodeCollaterals.fold(0L)(_.values.map(_.size.toLong).sum),
          "node_collateral_withdraw" -> globalSnapshot.nodeCollateralWithdrawals.fold(0L)(_.values.map(_.size.toLong).sum),
          "spend_action" -> globalSnapshot.spendActions.fold(0L)(_.values.map(_.size.toLong).sum),
          "update_node_parameters" -> globalSnapshot.updateNodeParameters.fold(0L)(_.size.toLong),
          "artifact" -> globalSnapshot.artifacts.fold(0L)(_.size.toLong),
          "reward" -> globalSnapshot.rewards.size.toLong,
          "delegate_reward" -> globalSnapshot.delegateRewards.fold(0L)(_.values.map(_.size.toLong).sum)
        )
        returnedEvents = returnedSCEvents.map(StateChannelEvent(_)) ++ returnedDAGEvents ++ heldDagEvents
        _ <- ConsensusLog.info(
          logger,
          Category.Proposal,
          currentOrdinal.show,
          "n/a",
          Event.ArtifactBuilt,
          "height" -> globalSnapshot.height.show,
          "subHeight" -> globalSnapshot.subHeight.show,
          "epoch" -> globalSnapshot.epochProgress.show,
          "stateProof.mptRoot" -> globalSnapshot.stateProof.mptRoot.map(_.show.take(12)).getOrElse("none"),
          "stateProof.balances" -> globalSnapshot.stateProof.balancesProof.show.take(12),
          "stateProof.delegStakes" -> globalSnapshot.stateProof.activeDelegatedStakes.map(_.show.take(12)).getOrElse("none"),
          "stateProof.nodeCollaterals" -> globalSnapshot.stateProof.activeNodeCollaterals.map(_.show.take(12)).getOrElse("none")
        )
        _ <- rewardsService.calculateAndStoreRewardsInfo(globalSnapshot, snapshotInfo)
      } yield (globalSnapshot, snapshotInfo, returnedEvents)
    }

    private def getReturnedDAGEvents(
      acceptanceResult: BlockAcceptanceResult
    ): Set[GlobalSnapshotEvent] =
      acceptanceResult.notAccepted.mapFilter {
        case (signedBlock, _: BlockAwaitReason) => DAGEvent(signedBlock).some
        case _                                  => none
      }.toSet
  }

}
