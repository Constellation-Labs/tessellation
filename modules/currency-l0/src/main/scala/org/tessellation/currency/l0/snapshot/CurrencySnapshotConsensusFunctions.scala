package org.tessellation.currency.l0.snapshot

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.balance.Amount
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensusFunctions
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class CurrencySnapshotConsensusFunctions[F[_]: Async: SecurityProvider]
    extends SnapshotConsensusFunctions[
      F,
      CurrencySnapshotStateProof,
      CurrencySnapshotEvent,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      ConsensusTrigger
    ] {}

object CurrencySnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Metrics](
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    blockAcceptanceManager: BlockAcceptanceManager[F],
    collateral: Amount
  ): CurrencySnapshotConsensusFunctions[F] = new CurrencySnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass(CurrencySnapshotConsensusFunctions.getClass)

    def getRequiredCollateral: Amount = collateral

    def consumeSignedMajorityArtifact(signedArtifact: Signed[CurrencyIncrementalSnapshot], context: CurrencySnapshotInfo): F[Unit] =
      stateChannelSnapshotService.consume(signedArtifact, context)

    def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent]
    ): F[(CurrencySnapshotArtifact, Set[CurrencySnapshotEvent])] = {

      val blocksForAcceptance = events
        .filter(_.height > lastArtifact.height)
        .toList

      for {
        lastArtifactHash <- lastArtifact.value.hashF
        currentOrdinal = lastArtifact.ordinal.next
        lastActiveTips <- lastArtifact.activeTips
        lastDeprecatedTips = lastArtifact.tips.deprecated

        (acceptanceResult, snapshotInfo) <- accept(
          blocksForAcceptance,
          lastContext,
          lastActiveTips,
          lastDeprecatedTips
        )

        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )

        (height, subHeight) <- getHeightAndSubHeight(lastArtifact, deprecated, remainedActive, accepted)

        returnedEvents = getReturnedEvents(acceptanceResult)
        stateProof <- snapshotInfo.stateProof

        artifact = CurrencyIncrementalSnapshot(
          currentOrdinal,
          height,
          subHeight,
          lastArtifactHash,
          accepted,
          SnapshotTips(
            deprecated = deprecated,
            remainedActive = remainedActive
          ),
          stateProof
        )
      } yield (artifact, returnedEvents)
    }

    private def accept(
      blocksForAcceptance: List[CurrencySnapshotEvent],
      lastSnapshotContext: CurrencySnapshotContext,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip]
    ) = for {
      acceptanceResult <- acceptBlocks(blocksForAcceptance, lastSnapshotContext, lastActiveTips, lastDeprecatedTips)

      transactionsRefs = lastSnapshotContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs
      updatedBalances = lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances
    } yield (acceptanceResult, CurrencySnapshotInfo(transactionsRefs, updatedBalances))

    private def acceptBlocks(
      blocksForAcceptance: List[CurrencySnapshotEvent],
      lastSnapshotContext: CurrencySnapshotContext,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip]
    ) = {
      val tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
      val context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTxRefs,
        tipUsages,
        collateral
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)
    }

    private def getReturnedEvents(
      acceptanceResult: BlockAcceptanceResult
    ): Set[CurrencySnapshotEvent] =
      acceptanceResult.notAccepted.mapFilter {
        case (signedBlock, _: BlockAwaitReason) => signedBlock.some
        case _                                  => none
      }.toSet
  }

  @derive(eqv, show)
  case class CannotApplyBlocksError(reasons: List[BlockNotAcceptedReason]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build currency snapshot ${reasons.show}"
  }
}
