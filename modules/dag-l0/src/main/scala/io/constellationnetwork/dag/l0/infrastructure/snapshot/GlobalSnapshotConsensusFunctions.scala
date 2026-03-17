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
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.delegatedStake.RewardsInfoStorage
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
import io.constellationnetwork.schema.transaction.Transaction
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

  def make[F[_]: Async: SecurityProvider: JsonSerializer](
    globalSnapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[F],
    collateral: Amount,
    rewardsService: RewardsService[F],
    eventCutter: EventCutter[F, StateChannelEvent, DAGEvent],
    updateNodeParametersCutter: UpdateNodeParametersCutter[F],
    environment: AppEnvironment,
    delegatedRewardsConfigProvider: DelegatedRewardsConfigProvider,
    v3MigrationOrdinal: SnapshotOrdinal,
    setSumFixOrdinal: SnapshotOrdinal,
    incrementalDelegatedStakingStartingOrdinal: SnapshotOrdinal,
    mptStore: MptStore[F, GlobalStateKey]
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass[F](getClass)

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
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
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
        getGlobalSnapshotByOrdinal
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
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
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

      for {
        lastArtifactHash <- getLastArtifactHash
        currentOrdinal = lastArtifact.ordinal.next
        currentEpochProgress = trigger match {
          case EventTrigger => lastArtifact.epochProgress
          case TimeTrigger  => lastArtifact.epochProgress.next
        }

        (scEvents, blocksForAcceptance) <- eventCutter.cut(
          scEventsBeforeCut.toList.sortBy(_.value.address),
          dagEvents.toList,
          snapshotContext,
          currentOrdinal
        )

        unpEventsForAcceptance <- updateNodeParametersCutter.cut(unpEventsBeforeCut.toList, snapshotContext, currentOrdinal)

        lastActiveTips <- lastArtifact.activeTips(Async[F], lastArtifactHasher)
        lastDeprecatedTips = lastArtifact.tips.deprecated

        // Derive lastFacilitators from the current-round facilitators set rather than
        // lastArtifact.proofs. Different nodes collect different numbers of signatures
        // for the same snapshot (gossip is non-deterministic), so proofs.size varies
        // per node. This causes divergent nodeOperatorRewards counts (and amounts)
        // because the facilitator pool is split by facilitators.size. Using the
        // current-round facilitators is deterministic: all nodes must receive all
        // facility declarations before advancing from CollectingFacilities, so
        // state.facilitators is identical across all consensus participants.
        lastFacilitators <- facilitators.toList.traverse { peerId =>
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

        _ <- ConsensusLog.info(
          logger,
          ConsensusLog.Proposal,
          currentOrdinal.show,
          "n/a",
          "event" -> "PROPOSAL_EVENTS",
          "trigger" -> trigger.toString,
          "events.total" -> events.size.toString,
          "dag" -> blocksForAcceptance.size.toString,
          "sc" -> scEvents.size.toString,
          "allowSpend" -> sortedAllowSpendEvents.size.toString,
          "tokenLock" -> sortedTokenLockEvents.size.toString,
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
              blocksForAcceptance.map(_.value),
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
        _ <- ConsensusLog.info(
          logger,
          ConsensusLog.Proposal,
          currentOrdinal.show,
          "n/a",
          "event" -> "ACCEPT_TIMING",
          "acceptDurationMs" -> (acceptEndMs - acceptStartMs).toString
        )
        _ <- ConsensusLog.info(
          logger,
          ConsensusLog.Proposal,
          currentOrdinal.show,
          "n/a",
          "event" -> "ACCEPTANCE_RESULTS",
          "blocks.accepted" -> acceptanceResult.accepted.size.toString,
          "blocks.notAccepted" -> acceptanceResult.notAccepted.size.toString,
          "allowSpend.accepted" -> allowSpendBlockAcceptanceResult.accepted.size.toString,
          "tokenLock.accepted" -> tokenLockBlockAcceptanceResult.accepted.size.toString,
          "delegStakeCreate.accepted" -> delegatedStakeAcceptanceResult.acceptedCreates.size.toString,
          "delegStakeCreate.rejected" -> delegatedStakeAcceptanceResult.notAcceptedCreates.size.toString,
          "delegStakeWithdraw.accepted" -> delegatedStakeAcceptanceResult.acceptedWithdrawals.size.toString,
          "delegStakeWithdraw.rejected" -> delegatedStakeAcceptanceResult.notAcceptedWithdrawals.size.toString,
          "nodeCollCreate.accepted" -> nodeCollateralAcceptanceResult.acceptedCreates.size.toString,
          "nodeCollCreate.rejected" -> nodeCollateralAcceptanceResult.notAcceptedCreates.size.toString,
          "nodeCollWithdraw.accepted" -> nodeCollateralAcceptanceResult.acceptedWithdrawals.size.toString,
          "nodeCollWithdraw.rejected" -> nodeCollateralAcceptanceResult.notAcceptedWithdrawals.size.toString,
          "scSnapshots" -> scSnapshots.size.toString,
          "rewards" -> acceptedRewardTxs.size.toString
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
          acceptedNnodeCollateralWithdrawals.some
        )
        returnedEvents = returnedSCEvents.map(StateChannelEvent(_)) ++ returnedDAGEvents
        _ <- ConsensusLog.info(
          logger,
          ConsensusLog.Proposal,
          currentOrdinal.show,
          "n/a",
          "event" -> "ARTIFACT_BUILT",
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
