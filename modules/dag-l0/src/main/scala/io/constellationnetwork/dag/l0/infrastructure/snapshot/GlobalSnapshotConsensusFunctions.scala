package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.dag.l0.config.DelegatedRewardsConfigProvider
import io.constellationnetwork.dag.l0.domain.snapshot.programs.UpdateNodeParametersCutter
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceResult
import io.constellationnetwork.node.shared.domain.event.EventCutter
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.snapshot.{RewardsInput, _}
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

  def make[F[_]: Async: SecurityProvider: JsonSerializer: KryoSerializer](
    globalSnapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[F],
    collateral: Amount,
    classicRewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent],
    delegatedRewards: DelegatedRewardsDistributor[F],
    eventCutter: EventCutter[F, StateChannelEvent, DAGEvent],
    updateNodeParametersCutter: UpdateNodeParametersCutter[F],
    environment: AppEnvironment,
    delegatedRewardsConfigProvider: DelegatedRewardsConfigProvider,
    v3MigrationOrdinal: SnapshotOrdinal
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    def getRequiredCollateral: Amount = collateral

    def getBalances(context: GlobalSnapshotContext): SortedMap[Address, Balance] = context.balances

    override def validateArtifact(
      lastSignedArtifact: Signed[GlobalSnapshotArtifact],
      lastContext: GlobalSnapshotContext,
      trigger: ConsensusTrigger,
      artifact: GlobalSnapshotArtifact,
      facilitators: Set[PeerId],
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
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

      def usingKryo = createProposalArtifact(
        lastSignedArtifact.ordinal,
        lastSignedArtifact,
        lastContext,
        Hasher.forKryo[F],
        trigger,
        events,
        facilitators,
        lastGlobalSnapshots,
        getGlobalSnapshotByOrdinal
      )

      def usingJson = createProposalArtifact(
        lastSignedArtifact.ordinal,
        lastSignedArtifact,
        lastContext,
        Hasher.forJson[F],
        trigger,
        events,
        facilitators,
        lastGlobalSnapshots,
        getGlobalSnapshotByOrdinal
      )

      def check(result: F[(GlobalSnapshotArtifact, GlobalSnapshotContext, Set[GlobalSnapshotEvent])]) =
        result.map {
          case (recreatedArtifact, context, _) =>
            if (recreatedArtifact === artifact)
              (artifact, context).asRight[InvalidArtifact]
            else
              ArtifactMismatch.asLeft[(GlobalSnapshotArtifact, GlobalSnapshotContext)]
        }

      check(usingJson).flatMap {
        case Left(_)  => check(usingKryo)
        case Right(a) => Async[F].pure(Right(a))
      }
    }

    def createProposalArtifact(
      lastKey: GlobalSnapshotKey,
      lastArtifact: Signed[GlobalSnapshotArtifact],
      snapshotContext: GlobalSnapshotContext,
      lastArtifactHasher: Hasher[F],
      trigger: ConsensusTrigger,
      events: Set[GlobalSnapshotEvent],
      facilitators: Set[PeerId],
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
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
      val asOfEpoch = delegatedConfig.emissionConfig.get(environment).map(_.asOfEpoch).getOrElse(EpochProgress.MaxValue)

      def shouldUseDelegatedRewards(currentOrdinal: SnapshotOrdinal, currentEpochProgress: EpochProgress): Boolean =
        currentOrdinal.value >= v3MigrationOrdinal.value &&
          currentEpochProgress.value.value >= asOfEpoch.value.value

      val classicRewardsFn = classicRewards
        .distribute(
          _: Signed[GlobalIncrementalSnapshot],
          _: SortedMap[Address, Balance],
          _: SortedSet[Signed[Transaction]],
          _: ConsensusTrigger,
          _: Set[GlobalSnapshotEvent],
          _: Option[DataCalculatedState]
        )
        .map { rewardTxs =>
          DelegationRewardsResult(
            delegatorRewardsMap = Map.empty,
            updatedCreateDelegatedStakes = SortedMap.empty,
            updatedWithdrawDelegatedStakes = SortedMap.empty,
            nodeOperatorRewards = rewardTxs,
            reservedAddressRewards = SortedSet.empty,
            withdrawalRewardTxs = SortedSet.empty,
            totalEmittedRewardsAmount = Amount(NonNegLong.unsafeFrom(rewardTxs.map(_.amount.value.value).sum))
          )
        }

      val rewardsWithFacilitators: List[(Address, PeerId)] => RewardsInput => F[DelegationRewardsResult] = {
        faciltators: List[(Address, PeerId)] =>
          {
            case ClassicRewardsInput(txs) =>
              classicRewardsFn(lastArtifact, snapshotContext.balances, txs, trigger, events, None)

            case DelegateRewardsInput(udsar, psu, ep) =>
              if (shouldUseDelegatedRewards(lastArtifact.ordinal.next, ep)) {
                delegatedRewards.distribute(snapshotContext, trigger, ep, faciltators, udsar, psu)
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
              lastGlobalSnapshots,
              getGlobalSnapshotByOrdinal
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
          if (delegatorRewardsMap.nonEmpty) Some(SortedMap.from(delegatorRewardsMap)) else None,
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
