package org.tessellation.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._

import scala.collection.immutable.SortedMap

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.node.shared.domain.block.processing._
import org.tessellation.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.tessellation.node.shared.domain.event.EventCutter
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.node.shared.infrastructure.snapshot._
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}
import org.tessellation.statechannel.{StateChannelOutput, StateChannelValidationType}

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

  def make[F[_]: Async: SecurityProvider](
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
      facilitators: Set[PeerId]
    )(implicit hasher: Hasher[F]): F[Either[InvalidArtifact, (GlobalSnapshotArtifact, GlobalSnapshotContext)]] = {
      val dagEvents = artifact.blocks.unsorted.map(_.block.asRight[StateChannelOutput])
      val scEvents = artifact.stateChannelSnapshots.toList.flatMap {
        case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _).asLeft[DAGEvent]).toList
      }
      val events = dagEvents ++ scEvents

      createProposalArtifact(lastSignedArtifact.ordinal, lastSignedArtifact, lastContext, trigger, events, facilitators).map {
        case (recreatedArtifact, context, _) =>
          if (recreatedArtifact === artifact)
            (artifact, context).asRight[InvalidArtifact]
          else
            ArtifactMismatch.asLeft[(GlobalSnapshotArtifact, GlobalSnapshotContext)]
      }
    }

    def createProposalArtifact(
      lastKey: GlobalSnapshotKey,
      lastArtifact: Signed[GlobalSnapshotArtifact],
      snapshotContext: GlobalSnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[GlobalSnapshotEvent],
      facilitators: Set[PeerId]
    )(implicit hasher: Hasher[F]): F[(GlobalSnapshotArtifact, GlobalSnapshotContext, Set[GlobalSnapshotEvent])] = {
      val (scEventsBeforeCut, dagEventsBeforeCut) = events.partitionMap(identity)

      val dagEvents = dagEventsBeforeCut.filter(_.height > lastArtifact.height)

      for {
        (scEvents, blocksForAcceptance) <- eventCutter.cut(scEventsBeforeCut.toList, dagEvents.toList)

        lastArtifactHash <- lastArtifact.value.hash
        currentOrdinal = lastArtifact.ordinal.next
        currentEpochProgress = trigger match {
          case EventTrigger => lastArtifact.epochProgress
          case TimeTrigger  => lastArtifact.epochProgress.next
        }

        lastActiveTips <- lastArtifact.activeTips
        lastDeprecatedTips = lastArtifact.tips.deprecated

        (acceptanceResult, scSnapshots, returnedSCEvents, acceptedRewardTxs, snapshotInfo, stateProof) <- globalSnapshotAcceptanceManager
          .accept(
            currentOrdinal,
            blocksForAcceptance,
            scEvents,
            snapshotContext,
            lastActiveTips,
            lastDeprecatedTips,
            rewards.distribute(lastArtifact, snapshotContext.balances, _, trigger, events),
            StateChannelValidationType.Full
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
          stateProof
        )
        returnedEvents = returnedSCEvents.map(_.asLeft[DAGEvent]).union(returnedDAGEvents)
      } yield (globalSnapshot, snapshotInfo, returnedEvents)
    }

    private def getReturnedDAGEvents(
      acceptanceResult: BlockAcceptanceResult
    ): Set[GlobalSnapshotEvent] =
      acceptanceResult.notAccepted.mapFilter {
        case (signedBlock, _: BlockAwaitReason) => signedBlock.asRight[StateChannelEvent].some
        case _                                  => none
      }.toSet
  }

}
