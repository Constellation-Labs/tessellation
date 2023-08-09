package org.tessellation.sdk.infrastructure.snapshot

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.bifunctor._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._

import scala.collection.immutable.SortedSet

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.dataApplication.{BaseDataApplicationL0Service, DataState, L0NodeContext}
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
    rewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot]]
  ): F[CurrencySnapshotCreationResult]
}

object CurrencySnapshotCreator {
  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    currencySnapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F],
    maybeDataApplicationService: Option[(L0NodeContext[F], BaseDataApplicationL0Service[F])]
  ): CurrencySnapshotCreator[F] = new CurrencySnapshotCreator[F] {
    private val logger = Slf4jLogger.getLogger

    def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent],
      rewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot]]
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

        maybeLastDataApplication = lastArtifact.data

        maybeLastDataState <- (maybeLastDataApplication, maybeDataApplicationService).mapN {
          case (lastDataApplication, (_, service)) =>
            service
              .deserializeState(lastDataApplication)
              .flatTap {
                case Left(err) => logger.warn(err)(s"Cannot deserialize custom state")
                case Right(a)  => logger.info(s"Deserialized state: ${a}")
              }
              .map(_.toOption)
              .handleErrorWith(err =>
                logger.error(err)(s"Unhandled exception during deserialization data application, fallback to empty state") >>
                  none[DataState].pure[F]
              )
        }.flatSequence

        maybeNewDataState <- (maybeDataApplicationService, maybeLastDataState).mapN {
          case (((nodeContext, service), lastState)) =>
            implicit val context: L0NodeContext[F] = nodeContext
            NonEmptyList
              .fromList(dataBlocks.distinctBy(_.value.roundId))
              .map(_.flatMap(_.value.updates))
              .map { updates =>
                service
                  .validateData(lastState, updates)
                  .flatTap { validated =>
                    Applicative[F].unlessA(validated.isValid)(logger.warn(s"Data application is invalid, errors: ${validated.toString}"))
                  }
                  .map(_.isValid)
                  .handleErrorWith(err =>
                    logger.error(err)(s"Unhandled exception during validating data application, assumed as invalid") >> false.pure[F]
                  )
                  .ifM(
                    service
                      .combine(lastState, updates)
                      .flatMap { state =>
                        service.serializeState(state)
                      }
                      .map(_.some)
                      .handleErrorWith(err =>
                        logger.error(err)(
                          s"Unhandled exception during combine and serialize data application, fallback to last data application"
                        ) >> maybeLastDataApplication.pure[F]
                      ),
                    logger.warn("Data application is not valid") >> maybeLastDataApplication.pure[F]
                  )
              }
              .getOrElse(maybeLastDataApplication.pure[F])
        }.flatSequence

        (acceptanceResult, acceptedRewardTxs, snapshotInfo, stateProof) <- currencySnapshotAcceptanceManager.accept(
          blocksForAcceptance,
          lastContext,
          lastActiveTips,
          lastDeprecatedTips,
          transactions =>
            rewards
              .map(_.distribute(lastArtifact, lastContext.snapshotInfo.balances, transactions, trigger))
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
          maybeNewDataState
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
