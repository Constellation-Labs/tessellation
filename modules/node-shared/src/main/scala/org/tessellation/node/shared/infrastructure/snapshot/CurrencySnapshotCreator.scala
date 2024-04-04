package org.tessellation.node.shared.infrastructure.snapshot

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.json.JsonSerializer
import org.tessellation.node.shared.config.types.SnapshotSizeConfig
import org.tessellation.node.shared.domain.block.processing._
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.node.shared.snapshot.currency.{CurrencySnapshotArtifact, CurrencySnapshotEvent}
import org.tessellation.schema._
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.security.Hasher
import org.tessellation.security.signature.Signed
import org.tessellation.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong

case class CurrencySnapshotCreationResult[Event](
  artifact: CurrencySnapshotArtifact,
  context: CurrencySnapshotContext,
  awaitingEvents: Set[Event],
  rejectedEvents: Set[Event]
)

trait CurrencySnapshotCreator[F[_]] {
  def createProposalArtifact(
    lastKey: SnapshotOrdinal,
    lastArtifact: Signed[CurrencySnapshotArtifact],
    lastContext: CurrencySnapshotContext,
    trigger: ConsensusTrigger,
    events: Set[CurrencySnapshotEvent],
    rewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    facilitators: Set[PeerId]
  ): F[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]
}

object CurrencySnapshotCreator {

  def make[F[_]: Async: Hasher: JsonSerializer](
    currencySnapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F],
    dataApplicationSnapshotAcceptanceManager: Option[DataApplicationSnapshotAcceptanceManager[F]],
    snapshotSizeConfig: SnapshotSizeConfig,
    currencyEventsCutter: CurrencyEventsCutter[F]
  ): CurrencySnapshotCreator[F] = new CurrencySnapshotCreator[F] {

    private def maxProposalSizeInBytes(facilitators: Set[PeerId]): PosLong =
      PosLong
        .from(
          snapshotSizeConfig.maxStateChannelSnapshotBinarySizeInBytes - (facilitators.size * snapshotSizeConfig.singleSignatureSizeInBytes)
        )
        .getOrElse(1L)

    def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent],
      rewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
      facilitators: Set[PeerId]
    ): F[CurrencySnapshotCreationResult[CurrencySnapshotEvent]] = {

      val maxArtifactSize = maxProposalSizeInBytes(facilitators)

      def createProposalWithSizeLimit(
        eventsForAcceptance: Set[CurrencySnapshotEvent],
        rejectedEvents: Set[CurrencySnapshotEvent] = Set.empty[CurrencySnapshotEvent],
        awaitedEvents: Set[CurrencySnapshotEvent] = Set.empty[CurrencySnapshotEvent]
      ): F[CurrencySnapshotCreationResult[CurrencySnapshotEvent]] =
        for {
          lastArtifactHash <- lastArtifact.value.hash
          currentOrdinal = lastArtifact.ordinal.next

          currentEpochProgress = trigger match {
            case EventTrigger => lastArtifact.epochProgress
            case TimeTrigger  => lastArtifact.epochProgress.next
          }
          lastActiveTips <- lastArtifact.activeTips
          lastDeprecatedTips = lastArtifact.tips.deprecated
          maybeLastDataApplication = lastArtifact.dataApplication

          (blocks: List[Signed[Block]], dataBlocks: List[Signed[DataApplicationBlock]]) =
            dataApplicationSnapshotAcceptanceManager match {
              case Some(_) => eventsForAcceptance.toList.partitionMap(identity)
              case None    => (eventsForAcceptance.collect { case Left(b) => b }.toList, List.empty)
            }

          dataApplicationAcceptanceResult <- dataApplicationSnapshotAcceptanceManager.flatTraverse(
            _.accept(maybeLastDataApplication, dataBlocks, lastArtifact.ordinal, currentOrdinal)
          )

          (acceptanceResult, acceptedRewardTxs, snapshotInfo, stateProof) <- currencySnapshotAcceptanceManager.accept(
            blocks,
            lastContext,
            currentOrdinal,
            lastActiveTips,
            lastDeprecatedTips,
            transactions =>
              rewards
                .map(
                  _.distribute(
                    lastArtifact,
                    lastContext.snapshotInfo.balances,
                    transactions,
                    trigger,
                    events,
                    dataApplicationAcceptanceResult.map(_.calculatedState)
                  )
                )
                .getOrElse(SortedSet.empty[RewardTransaction].pure[F])
          )

          (awaitingBlocks, rejectedBlocks) = acceptanceResult.notAccepted.partitionMap {
            case (b, _: BlockAwaitReason)     => b.asRight
            case (b, _: BlockRejectionReason) => b.asLeft
          }

          (deprecated, remainedActive, accepted) = getUpdatedTips(
            lastActiveTips,
            lastDeprecatedTips,
            acceptanceResult,
            currentOrdinal
          )

          (height, subHeight) <- getHeightAndSubHeight(lastArtifact, deprecated, remainedActive, accepted)

          artifact = CurrencyIncrementalSnapshot(
            currentOrdinal,
            height,
            subHeight,
            lastArtifactHash,
            accepted,
            acceptedRewardTxs,
            SnapshotTips(
              deprecated = deprecated,
              remainedActive = remainedActive
            ),
            stateProof,
            currentEpochProgress,
            dataApplicationAcceptanceResult.map(_.dataApplicationPart)
          )

          context = CurrencySnapshotContext(lastContext.address, snapshotInfo)
          newAwaitingEvents = awaitingBlocks
            .map(_.asLeft[Signed[DataApplicationBlock]])
            .toSet[CurrencySnapshotEvent] ++ awaitedEvents
          newRejectedEvents = rejectedBlocks.map(_.asLeft[Signed[DataApplicationBlock]]).toSet[CurrencySnapshotEvent] ++ rejectedEvents
          acceptedEvents = acceptanceResult.accepted.map(_._1)

          artifactSize: Int <- JsonSerializer[F].serialize(artifact).map(_.length)

          result <-
            if (artifactSize <= maxArtifactSize) {
              CurrencySnapshotCreationResult[CurrencySnapshotEvent](
                artifact,
                context,
                newAwaitingEvents,
                newRejectedEvents
              ).pure[F]
            } else {
              currencyEventsCutter.cut(currentOrdinal, acceptedEvents, dataBlocks).flatMap {
                case Some((remainingAcceptedEvents, sizeAwaitedEvent)) =>
                  createProposalWithSizeLimit(remainingAcceptedEvents, newRejectedEvents, newAwaitingEvents + sizeAwaitedEvent)
                case None =>
                  UnableToReduceProposalByCutting(currentOrdinal)
                    .raiseError[F, CurrencySnapshotCreationResult[CurrencySnapshotEvent]]
              }
            }
        } yield result

      createProposalWithSizeLimit(events)
    }

    protected def getHeightAndSubHeight(
      lastGS: CurrencySnapshotArtifact,
      deprecated: Set[DeprecatedTip],
      remainedActive: Set[ActiveTip],
      accepted: Set[BlockAsActiveTip]
    ): F[(Height, SubHeight)] = {
      val tipHeights = (deprecated.map(_.block.height) ++ remainedActive.map(_.block.height) ++ accepted
        .map(_.block.height)).toList

      for {
        height <- tipHeights.minimumOption.liftTo[F](NoTipsRemaining)

        _ <-
          if (height < lastGS.height)
            InvalidHeight(lastGS.height, height).raiseError
          else
            Applicative[F].unit

        subHeight = if (height === lastGS.height) lastGS.subHeight.next else SubHeight.MinValue
      } yield (height, subHeight)
    }

    protected def getUpdatedTips(
      lastActive: SortedSet[ActiveTip],
      lastDeprecated: SortedSet[DeprecatedTip],
      acceptanceResult: BlockAcceptanceResult,
      currentOrdinal: SnapshotOrdinal
    ): (SortedSet[DeprecatedTip], SortedSet[ActiveTip], SortedSet[BlockAsActiveTip]) = {
      val usagesUpdate = acceptanceResult.contextUpdate.parentUsages
      val accepted =
        acceptanceResult.accepted.map { case (block, usages) => BlockAsActiveTip(block, usages) }.toSortedSet
      val (remainedActive, newlyDeprecated) = lastActive.partitionMap { at =>
        val maybeUpdatedUsage = usagesUpdate.get(at.block)
        Either.cond(
          maybeUpdatedUsage.exists(_ >= deprecationThreshold),
          DeprecatedTip(at.block, currentOrdinal),
          maybeUpdatedUsage.map(uc => at.copy(usageCount = uc)).getOrElse(at)
        )
      }.bimap(_.toSortedSet, _.toSortedSet)
      val lowestActiveIntroducedAt = remainedActive.toList.map(_.introducedAt).minimumOption.getOrElse(currentOrdinal)
      val remainedDeprecated = lastDeprecated.filter(_.deprecatedAt > lowestActiveIntroducedAt)

      (remainedDeprecated | newlyDeprecated, remainedActive, accepted)
    }
  }
}

case class UnableToReduceProposalByCutting(ordinal: SnapshotOrdinal) extends NoStackTrace {
  override val getMessage = s"Unable to reduce proposal by cutting for ${ordinal.show}"
}
