package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.dag.l0.domain.snapshot.programs.UpdateNodeParametersCutter
import io.constellationnetwork.dag.l0.infrastructure.rewards.RewardsService
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
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.delegatedStake.RewardsInfoStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global.GlobalSnapshotAcceptanceManager
import io.constellationnetwork.node.shared.snapshot.global._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake.UpdateDelegatedStake
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.Transaction
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelValidationType}
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

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
    incrementalDelegatedStakingStartingOrdinal: SnapshotOrdinal
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromName[F]("GlobalSnapshotConsensusFunctions")

    def getRequiredCollateral: Amount = collateral

    def getBalances(context: GlobalSnapshotContext): SortedMap[Address, Balance] = context.balances

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

      def usingJson = createProposalArtifact(
        lastSignedArtifact.ordinal,
        lastSignedArtifact,
        lastContext,
        Hasher.forJson[F],
        trigger,
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
          scEventsBeforeCut.toList,
          dagEvents.toList,
          snapshotContext,
          currentOrdinal
        )

        // Debug logging for StateChannel events
        _ <- logger.debug(
          s"[SCEvents] Ordinal=$currentOrdinal inputEvents=${events.size} scEventsBeforeCut=${scEventsBeforeCut.size} scEventsAfterCut=${scEvents.size}"
        )

        unpEventsForAcceptance <- updateNodeParametersCutter.cut(unpEventsBeforeCut.toList, snapshotContext, currentOrdinal)

        lastActiveTips <- lastArtifact.activeTips(Async[F], lastArtifactHasher)
        lastDeprecatedTips = lastArtifact.tips.deprecated

        lastFacilitators <- lastArtifact.proofs.toList.traverse {
          case SignatureProof(id, _) => id.toAddress.map(_ -> id.toPeerId)
        }

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
              allowSpendEventsForAcceptance.toList.map(_.value),
              tokenLockEventsForAcceptance.toList.map(_.value),
              scEvents.map(_.value),
              unpEventsForAcceptance.map(_.updateNodeParameters),
              cdsEventsForAcceptance.toList.map(_.value),
              wdsEventsForAcceptance.toList.map(_.value),
              cncEventsForAcceptance.toList.map(_.value),
              wncEventsForAcceptance.toList.map(_.value),
              snapshotContext,
              lastActiveTips,
              lastDeprecatedTips,
              rewardsWithFacilitators(lastFacilitators),
              StateChannelValidationType.Full,
              getGlobalSnapshotByOrdinal
            )
        // Debug logging for StateChannel acceptance results
        _ <- logger.debug(
          s"[SCAccepted] Ordinal=$currentOrdinal scSnapshots=${scSnapshots.size} returnedSCEvents=${returnedSCEvents.size} addresses=${scSnapshots.keys
              .mkString(",")}"
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
