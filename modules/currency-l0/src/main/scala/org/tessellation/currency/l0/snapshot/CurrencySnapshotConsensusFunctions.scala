package org.tessellation.currency.l0.snapshot

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.dataApplication.{BaseDataApplicationL0Service, DataState, L0NodeContext}
import org.tessellation.currency.l0.node.L0NodeContext
import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot.{CurrencySnapshotAcceptanceManager, SnapshotConsensusFunctions}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class CurrencySnapshotConsensusFunctions[F[_]: Async: SecurityProvider]
    extends SnapshotConsensusFunctions[
      F,
      CurrencyTransaction,
      CurrencyBlock,
      CurrencySnapshotEvent,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      ConsensusTrigger
    ] {}

object CurrencySnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Metrics](
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    currencySnapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F],
    collateral: Amount,
    rewards: Rewards[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot],
    maybeDataApplicationService: Option[BaseDataApplicationL0Service[F]],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
  ): CurrencySnapshotConsensusFunctions[F] = new CurrencySnapshotConsensusFunctions[F] {

    implicit val nodeContext: L0NodeContext[F] = L0NodeContext.make[F](lastGlobalSnapshotStorage, snapshotStorage)

    private val logger = Slf4jLogger.getLoggerFromClass(CurrencySnapshotConsensusFunctions.getClass)

    def getRequiredCollateral: Amount = collateral

    def getBalances(context: CurrencySnapshotContext): SortedMap[Address, Balance] = context.snapshotInfo.balances

    def consumeSignedMajorityArtifact(
      signedArtifact: Signed[CurrencyIncrementalSnapshot],
      context: CurrencySnapshotContext
    ): F[Unit] =
      stateChannelSnapshotService.consume(signedArtifact, context)

    def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent]
    ): F[(CurrencySnapshotArtifact, CurrencySnapshotContext, Set[CurrencySnapshotEvent])] = {

      val (blocks: List[Signed[CurrencyBlock]], dataBlocks: List[Signed[DataApplicationBlock]]) =
        events
          .filter(_.isLeft || maybeDataApplicationService.isDefined)
          .toList
          .partitionMap(identity)

      val blocksForAcceptance = blocks
        .filter(_.height > lastArtifact.height)
        .toList

      for {
        lastArtifactHash <- lastArtifact.value.hashF
        currentOrdinal = lastArtifact.ordinal.next
        lastActiveTips <- lastArtifact.activeTips
        lastDeprecatedTips = lastArtifact.tips.deprecated

        maybeLastDataApplication = lastArtifact.data

        maybeLastDataState <- (maybeLastDataApplication, maybeDataApplicationService).mapN {
          case ((lastDataApplication, service)) =>
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
          case ((service, lastState)) =>
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
          rewards.distribute(lastArtifact, lastContext.snapshotInfo.balances, _, trigger)
        )

        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )

        (height, subHeight) <- getHeightAndSubHeight(lastArtifact, deprecated, remainedActive, accepted)

        returnedEvents = getReturnedEvents(acceptanceResult)

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
          maybeNewDataState
        )
      } yield (artifact, CurrencySnapshotContext(lastContext.address, snapshotInfo), returnedEvents)
    }

    private def getReturnedEvents(
      acceptanceResult: BlockAcceptanceResult[CurrencyBlock]
    ): Set[CurrencySnapshotEvent] =
      acceptanceResult.notAccepted.mapFilter {
        case (signedBlock, _: BlockAwaitReason) => signedBlock.asLeft[Signed[DataApplicationBlock]].some
        case _                                  => none
      }.toSet
  }
}
