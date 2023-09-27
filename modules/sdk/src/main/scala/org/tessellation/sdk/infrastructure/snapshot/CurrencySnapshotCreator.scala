package org.tessellation.sdk.infrastructure.snapshot

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.tessellation.currency.dataApplication._
import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.sdk.snapshot.currency.{CurrencySnapshotArtifact, CurrencySnapshotEvent}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.syntax.sortedCollection.sortedSetSyntax

import org.typelevel.log4cats.slf4j.Slf4jLogger

case class CurrencySnapshotCreationResult(
  artifact: CurrencySnapshotArtifact,
  context: CurrencySnapshotContext,
  awaitingBlocks: Set[Signed[Block]],
  rejectedBlocks: Set[Signed[Block]]
)

trait CurrencySnapshotCreator[F[_]] {
  def createProposalArtifact(
    lastKey: SnapshotOrdinal,
    lastArtifact: Signed[CurrencySnapshotArtifact],
    lastContext: CurrencySnapshotContext,
    trigger: ConsensusTrigger,
    events: Set[CurrencySnapshotEvent],
    rewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]]
  ): F[CurrencySnapshotCreationResult]
}

object CurrencySnapshotCreator {
  case class CalculatedStateDoesNotMatchOrdinal(calculatedStateOrdinal: SnapshotOrdinal, expectedOrdinal: SnapshotOrdinal)
      extends NoStackTrace {
    override def getMessage(): String =
      s"Calculated state ordinal=${calculatedStateOrdinal.show} does not match expected ordinal=${expectedOrdinal.show}"
  }

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    currencySnapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F],
    maybeDataApplicationService: Option[(L0NodeContext[F], BaseDataApplicationL0Service[F])]
  ): CurrencySnapshotCreator[F] = new CurrencySnapshotCreator[F] {
    private val logger = Slf4jLogger.getLogger

    def expectCalculatedStateOrdinal(
      expectedOrdinal: SnapshotOrdinal
    )(calculatedState: (SnapshotOrdinal, DataCalculatedState)): F[DataCalculatedState] =
      calculatedState match {
        case (ordinal, state) =>
          CalculatedStateDoesNotMatchOrdinal(ordinal, expectedOrdinal)
            .raiseError[F, Unit]
            .unlessA(ordinal === expectedOrdinal)
            .as(state)
      }

    def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent],
      rewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]]
    ): F[CurrencySnapshotCreationResult] = {

      val (blocks: List[Signed[Block]], dataBlocks: List[Signed[DataApplicationBlock]]) =
        events
          .filter(_.isLeft || maybeDataApplicationService.isDefined)
          .toList
          .partitionMap(identity)

      val blocksForAcceptance = blocks

      for {
        lastArtifactHash <- lastArtifact.value.hashF
        currentOrdinal = lastArtifact.ordinal.next
        currentEpochProgress = trigger match {
          case EventTrigger => lastArtifact.epochProgress
          case TimeTrigger  => lastArtifact.epochProgress.next
        }
        lastActiveTips <- lastArtifact.activeTips
        lastDeprecatedTips = lastArtifact.tips.deprecated

        maybeLastDataApplication = lastArtifact.dataApplication.map(_.onChainState)

        maybeLastDataOnChainState <- (maybeLastDataApplication, maybeDataApplicationService).mapN {
          case (lastDataApplication, (_, service)) =>
            service
              .deserializeState(lastDataApplication)
              .flatTap {
                case Left(err) => logger.warn(err)(s"Cannot deserialize custom state")
                case Right(a)  => logger.info(s"Deserialized state: $a")
              }
              .map(_.toOption)
              .handleErrorWith(err =>
                logger.error(err)(s"Unhandled exception during deserialization data application, fallback to empty state") >>
                  none[DataOnChainState].pure[F]
              )
        }.flatSequence

        maybeNewDataState <- (maybeDataApplicationService, maybeLastDataOnChainState).mapN {
          case (((nodeContext, service), lastState)) =>
            implicit val context: L0NodeContext[F] = nodeContext
            NonEmptyList
              .fromList(dataBlocks.distinctBy(_.value.roundId))
              .map(_.flatMap(_.value.updates))
              .map { updates =>
                service.getCalculatedState
                  .flatMap(expectCalculatedStateOrdinal(lastArtifact.ordinal))
                  .flatMap { calculatedState =>
                    service.validateData(DataState(lastState, calculatedState), updates)
                  }
                  .flatTap { validated =>
                    Applicative[F].unlessA(validated.isValid)(logger.warn(s"Data application is invalid, errors: ${validated.toString}"))
                  }
                  .map(_.isValid)
                  .handleErrorWith(err =>
                    logger.error(err)(s"Unhandled exception during validating data application, assumed as invalid").as(false)
                  )
                  .ifF(updates.toList, List.empty[Signed[DataUpdate]])
              }
              .getOrElse(List.empty[Signed[DataUpdate]].pure[F])
              .flatMap { updates =>
                service.getCalculatedState
                  .flatMap(expectCalculatedStateOrdinal(lastArtifact.ordinal))
                  .map(DataState(lastState, _))
                  .flatMap(service.combine(_, updates))
                  .flatTap(state => service.setCalculatedState(currentOrdinal, state.calculated))
                  .flatMap(state => service.serializeState(state.onChain))
              }
              .map(_.some)
              .handleErrorWith(err =>
                logger
                  .error(err)(
                    s"Unhandled exception during combine and serialize data application, fallback to last data application"
                  )
                  .as(maybeLastDataApplication)
              )
        }.flatSequence

        maybeCalculatedState <- maybeDataApplicationService.traverse {
          case (nodeContext, service) =>
            implicit val context = nodeContext

            service.getCalculatedState
              .flatMap(expectCalculatedStateOrdinal(currentOrdinal))
        }

        (acceptanceResult, acceptedRewardTxs, snapshotInfo, stateProof) <- currencySnapshotAcceptanceManager.accept(
          blocksForAcceptance,
          lastContext,
          lastActiveTips,
          lastDeprecatedTips,
          transactions =>
            rewards
              .map(_.distribute(lastArtifact, lastContext.snapshotInfo.balances, transactions, trigger, events, maybeCalculatedState))
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

        maybeSerializedDataBlocks <- maybeDataApplicationService.traverse {
          case (_, service) =>
            dataBlocks.traverse(service.serializeBlock)
        }

        maybeCalculatedStateHash <- (maybeDataApplicationService, maybeCalculatedState).traverseN {
          case ((context, service), calculatedState) =>
            implicit val c = context

            service.hashCalculatedState(calculatedState)
        }

        maybeDataApplicationPart = (maybeNewDataState, maybeSerializedDataBlocks, maybeCalculatedStateHash).mapN {
          DataApplicationPart(_, _, _)
        }

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
          maybeDataApplicationPart
        )
      } yield
        CurrencySnapshotCreationResult(
          artifact,
          CurrencySnapshotContext(lastContext.address, snapshotInfo),
          awaitingBlocks.toSet,
          rejectedBlocks.toSet
        )
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
