package io.constellationnetwork.node.shared.infrastructure.snapshot

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

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSnapshotSync
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.SnapshotSizeConfig
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.infrastructure.consensus.ValidationErrorStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.currencyMessage.CurrencyMessage
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.schema.transaction.RewardTransaction
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

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
    lastArtifactHasher: Hasher[F],
    trigger: ConsensusTrigger,
    events: Set[CurrencySnapshotEvent],
    rewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    facilitators: Set[PeerId],
    feeTransactionFn: Option[() => SortedSet[Signed[FeeTransaction]]],
    artifactsFn: Option[() => SortedSet[SharedArtifact]],
    lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]
}

object CurrencySnapshotCreator {

  def make[F[_]: Async: JsonSerializer](
    currencySnapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F],
    dataApplicationSnapshotAcceptanceManager: Option[DataApplicationSnapshotAcceptanceManager[F]],
    snapshotSizeConfig: SnapshotSizeConfig,
    currencyEventsCutter: CurrencyEventsCutter[F],
    currencySnapshotValidationErrorStorage: ValidationErrorStorage[F, CurrencySnapshotEvent, BlockRejectionReason]
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
      lastArtifactHasher: Hasher[F],
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent],
      rewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
      facilitators: Set[PeerId],
      feeTransactionFn: Option[() => SortedSet[Signed[FeeTransaction]]],
      artifactsFn: Option[() => SortedSet[SharedArtifact]],
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
    )(implicit hasher: Hasher[F]): F[CurrencySnapshotCreationResult[CurrencySnapshotEvent]] = {
      val maxArtifactSize = maxProposalSizeInBytes(facilitators)

      def createProposalWithSizeLimit(
        eventsForAcceptance: Set[CurrencySnapshotEvent],
        rejectedEvents: Set[CurrencySnapshotEvent] = Set.empty[CurrencySnapshotEvent],
        awaitedEvents: Set[CurrencySnapshotEvent] = Set.empty[CurrencySnapshotEvent]
      ): F[CurrencySnapshotCreationResult[CurrencySnapshotEvent]] =
        for {
          lastArtifactHash <- lastArtifactHasher.hash(lastArtifact.value)
          currentOrdinal = lastArtifact.ordinal.next

          currentEpochProgress = trigger match {
            case EventTrigger => lastArtifact.epochProgress
            case TimeTrigger  => lastArtifact.epochProgress.next
          }
          lastActiveTips <- lastArtifact.activeTips(Async[F], lastArtifactHasher)
          lastDeprecatedTips = lastArtifact.tips.deprecated
          maybeLastDataApplication = lastArtifact.dataApplication

          (
            blocks: List[Signed[Block]],
            dataBlocks: List[Signed[DataApplicationBlock]],
            messages: List[Signed[CurrencyMessage]],
            globalSnapshotSyncEvents: List[Signed[GlobalSnapshotSync]],
            tokenLockBlocks: List[Signed[TokenLockBlock]],
            allowSpendBlocks: List[Signed[AllowSpendBlock]]
          ) =
            dataApplicationSnapshotAcceptanceManager match {
              case Some(_) =>
                eventsForAcceptance.toList.foldLeft(
                  (
                    List.empty[Signed[Block]],
                    List.empty[Signed[DataApplicationBlock]],
                    List.empty[Signed[CurrencyMessage]],
                    List.empty[Signed[GlobalSnapshotSync]],
                    List.empty[Signed[TokenLockBlock]],
                    List.empty[Signed[AllowSpendBlock]]
                  )
                ) {
                  case ((l1, l2, l3, l4, l5, l6), elem) =>
                    elem match {
                      case BlockEvent(b)                 => (b :: l1, l2, l3, l4, l5, l6)
                      case DataApplicationBlockEvent(db) => (l1, db :: l2, l3, l4, l5, l6)
                      case CurrencyMessageEvent(cm)      => (l1, l2, cm :: l3, l4, l5, l6)
                      case GlobalSnapshotSyncEvent(gsse) => (l1, l2, l3, gsse :: l4, l5, l6)
                      case TokenLockBlockEvent(tlb)      => (l1, l2, l3, l4, tlb :: l5, l6)
                      case AllowSpendBlockEvent(asb)     => (l1, l2, l3, l4, l5, asb :: l6)
                      case _                             => (l1, l2, l3, l4, l5, l6)
                    }
                }
              case None =>
                (
                  eventsForAcceptance.collect { case BlockEvent(b) => b }.toList,
                  List.empty,
                  eventsForAcceptance.collect { case CurrencyMessageEvent(cm) => cm }.toList,
                  eventsForAcceptance.collect { case GlobalSnapshotSyncEvent(gsse) => gsse }.toList,
                  eventsForAcceptance.collect { case TokenLockBlockEvent(tlb) => tlb }.toList,
                  eventsForAcceptance.collect { case AllowSpendBlockEvent(asb) => asb }.toList
                )
            }

          dataApplicationAcceptanceResult <- dataApplicationSnapshotAcceptanceManager.flatTraverse(
            _.accept(maybeLastDataApplication, dataBlocks, lastArtifact.ordinal, currentOrdinal)
          )

          sharedArtifactsForAcceptance = dataApplicationAcceptanceResult
            .map(_.sharedArtifacts)
            .orElse(artifactsFn.map(f => f()))
            .getOrElse(SortedSet.empty[SharedArtifact])

          feeTransactions = dataApplicationAcceptanceResult
            .map(result => SortedSet.from(result.feeTransactions))
            .orElse(feeTransactionFn.map(f => f()))

          currencySnapshotAcceptanceResult <-
            currencySnapshotAcceptanceManager
              .accept(
                blocks,
                tokenLockBlocks,
                allowSpendBlocks,
                messages,
                feeTransactions,
                globalSnapshotSyncEvents,
                sharedArtifactsForAcceptance,
                lastContext,
                currentOrdinal,
                currentEpochProgress,
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
                    .getOrElse(SortedSet.empty[RewardTransaction].pure[F]),
                facilitators,
                lastGlobalSnapshots,
                getGlobalSnapshotByOrdinal,
                lastArtifact.globalSyncView
              )

          rejectedBlockEvents = currencySnapshotAcceptanceResult.block.notAccepted.collect {
            case (b, r: BlockRejectionReason) => (BlockEvent(b), r)
          }
          _ <- currencySnapshotValidationErrorStorage.add(rejectedBlockEvents)

          rejectedDataBlockEvents = dataApplicationAcceptanceResult.map(_.notAccepted).getOrElse(List.empty).collect {
            case (b, r: BlockRejectionReason) => (DataApplicationBlockEvent(b), r)
          }
          _ <- currencySnapshotValidationErrorStorage.add(rejectedDataBlockEvents)

          rejectedMessageEvents = currencySnapshotAcceptanceResult.messages.notAccepted.map(m =>
            (CurrencyMessageEvent(m), InvalidCurrencyMessageEvent)
          )
          _ <- currencySnapshotValidationErrorStorage.add(rejectedMessageEvents)

          (awaitingBlocks, rejectedBlocks) = currencySnapshotAcceptanceResult.block.notAccepted.partitionMap {
            case (b, _: BlockAwaitReason)     => b.asRight
            case (b, _: BlockRejectionReason) => b.asLeft
          }

          (deprecated, remainedActive, accepted) = getUpdatedTips(
            lastActiveTips,
            lastDeprecatedTips,
            currencySnapshotAcceptanceResult.block,
            currentOrdinal
          )

          (height, subHeight) <- getHeightAndSubHeight(lastArtifact, deprecated, remainedActive, accepted)

          context = CurrencySnapshotContext(lastContext.address, currencySnapshotAcceptanceResult.info)

          newAwaitingEvents = awaitingBlocks.map(BlockEvent(_)).toSet[CurrencySnapshotEvent] ++ awaitedEvents

          newRejectedEvents = rejectedBlocks.map(BlockEvent(_)).toSet[CurrencySnapshotEvent] ++
            currencySnapshotAcceptanceResult.messages.notAccepted.map(CurrencyMessageEvent(_)).toSet[CurrencySnapshotEvent] ++
            currencySnapshotAcceptanceResult.globalSnapshotSync.notAccepted.map(GlobalSnapshotSyncEvent(_)) ++
            currencySnapshotAcceptanceResult.tokenLockBlock.notAccepted.map { case (block, _) => TokenLockBlockEvent(block) } ++
            currencySnapshotAcceptanceResult.allowSpendBlock.notAccepted.map { case (block, _) => AllowSpendBlockEvent(block) } ++
            rejectedEvents

          acceptedEvents = currencySnapshotAcceptanceResult.block.accepted.map(_._1)

          artifact = CurrencyIncrementalSnapshot(
            currentOrdinal,
            height,
            subHeight,
            lastArtifactHash,
            accepted,
            currencySnapshotAcceptanceResult.rewards,
            SnapshotTips(
              deprecated = deprecated,
              remainedActive = remainedActive
            ),
            currencySnapshotAcceptanceResult.stateProof,
            currentEpochProgress,
            dataApplicationAcceptanceResult.map(_.dataApplicationPart),
            Option.when(currencySnapshotAcceptanceResult.info.lastMessages.nonEmpty)(
              currencySnapshotAcceptanceResult.messages.accepted.toSortedSet
            ),
            currencySnapshotAcceptanceResult.globalSnapshotSync.accepted.toSortedSet.some,
            currencySnapshotAcceptanceResult.feeTransactions,
            currencySnapshotAcceptanceResult.sharedArtifacts.some,
            currencySnapshotAcceptanceResult.allowSpendBlock.accepted.toSortedSet.some,
            currencySnapshotAcceptanceResult.tokenLockBlock.accepted.toSortedSet.some,
            currencySnapshotAcceptanceResult.globalSyncView.some
          )

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
              currencyEventsCutter
                .cut(
                  currentOrdinal,
                  acceptedEvents,
                  currencySnapshotAcceptanceResult.tokenLockBlock.accepted,
                  dataBlocks,
                  currencySnapshotAcceptanceResult.messages.accepted
                )
                .flatMap {
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
