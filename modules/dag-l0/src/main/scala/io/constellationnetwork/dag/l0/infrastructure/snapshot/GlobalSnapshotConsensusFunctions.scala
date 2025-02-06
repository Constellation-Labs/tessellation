package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event._
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import io.constellationnetwork.node.shared.domain.event.EventCutter
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelValidationType}

import eu.timepit.refined.auto._

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
    rewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent],
    eventCutter: EventCutter[F, StateChannelEvent, DAGEvent]
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    def getRequiredCollateral: Amount = collateral

    def getBalances(context: GlobalSnapshotContext): SortedMap[Address, Balance] = context.balances

    override def validateArtifact(
      lastSignedArtifact: Signed[GlobalSnapshotArtifact],
      lastContext: GlobalSnapshotContext,
      trigger: ConsensusTrigger,
      artifact: GlobalSnapshotArtifact,
      facilitators: Set[PeerId],
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
    )(implicit hasher: Hasher[F]): F[Either[InvalidArtifact, (GlobalSnapshotArtifact, GlobalSnapshotContext)]] = {
      val dagEvents = artifact.blocks.unsorted.map(_.block).map(DAGEvent(_))
      val scEvents = artifact.stateChannelSnapshots.toList.flatMap {
        case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _)).map(StateChannelEvent(_)).toList
      }
      val allowSpendEvents = artifact.allowSpendBlocks.map(_.toList.map(AllowSpendEvent(_))).getOrElse(List.empty)
      val events: Set[GlobalSnapshotEvent] = dagEvents ++ scEvents ++ allowSpendEvents

      def usingKryo = createProposalArtifact(
        lastSignedArtifact.ordinal,
        lastSignedArtifact,
        lastContext,
        Hasher.forKryo[F],
        trigger,
        events,
        facilitators,
        lastGlobalSnapshots
      )

      def usingJson = createProposalArtifact(
        lastSignedArtifact.ordinal,
        lastSignedArtifact,
        lastContext,
        Hasher.forJson[F],
        trigger,
        events,
        facilitators,
        lastGlobalSnapshots
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
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
    )(implicit hasher: Hasher[F]): F[(GlobalSnapshotArtifact, GlobalSnapshotContext, Set[GlobalSnapshotEvent])] = {
      val scEventsBeforeCut = events.collect { case sc: StateChannelEvent => sc }
      val dagEventsBeforeCut = events.collect { case d: DAGEvent => d }
      val allowSpendEventsForAcceptance = events.collect { case as: AllowSpendEvent => as }

      val dagEvents = dagEventsBeforeCut.filter(_.value.height > lastArtifact.height)

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

        lastActiveTips <- lastArtifact.activeTips(Async[F], lastArtifactHasher)
        lastDeprecatedTips = lastArtifact.tips.deprecated

        (
          acceptanceResult,
          allowSpendBlockAcceptanceResult,
          scSnapshots,
          returnedSCEvents,
          acceptedRewardTxs,
          snapshotInfo,
          stateProof,
          spendActions
        ) <-
          globalSnapshotAcceptanceManager
            .accept(
              currentOrdinal,
              currentEpochProgress,
              blocksForAcceptance.map(_.value),
              allowSpendEventsForAcceptance.toList.map(_.value),
              scEvents.map(_.value),
              snapshotContext,
              lastActiveTips,
              lastDeprecatedTips,
              rewards.distribute(lastArtifact, snapshotContext.balances, _, trigger, events),
              StateChannelValidationType.Full,
              lastGlobalSnapshots
            )
        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )

        (height, subHeight) <- getHeightAndSubHeight(lastArtifact, deprecated, remainedActive, accepted)

        returnedDAGEvents = getReturnedDAGEvents(acceptanceResult)

        globalSnapshot = GlobalIncrementalSnapshot(
          currentOrdinal,
          height,
          subHeight,
          lastArtifactHash,
          accepted,
          scSnapshots,
          acceptedRewardTxs,
          currentEpochProgress,
          GlobalSnapshot.nextFacilitators,
          SnapshotTips(
            deprecated = deprecated,
            remainedActive = remainedActive
          ),
          stateProof,
          SortedSet.from(allowSpendBlockAcceptanceResult.accepted).some,
          SortedMap.from(spendActions).some
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
