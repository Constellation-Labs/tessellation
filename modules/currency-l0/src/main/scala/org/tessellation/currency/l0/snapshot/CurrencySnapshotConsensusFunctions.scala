package org.tessellation.currency.l0.snapshot

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
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
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.balance.Amount
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensusFunctions
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import derevo.cats.{eqv, show}
import derevo.derive
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

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    blockAcceptanceManager: BlockAcceptanceManager[F, CurrencyTransaction, CurrencyBlock],
    collateral: Amount
  ): CurrencySnapshotConsensusFunctions[F] = new CurrencySnapshotConsensusFunctions[F] {

    def createContext(
      lastSnapshotContext: CurrencySnapshotInfo,
      lastSnapshot: CurrencySnapshotArtifact,
      snapshot: Signed[CurrencySnapshotArtifact]
    ): F[CurrencySnapshotInfo] = for {
      lastActiveTips <- lastSnapshot.activeTips
      lastDeprecatedTips = lastSnapshot.tips.deprecated

      blocksForAcceptance = snapshot.blocks.toList.map(_.block)

      (acceptanceResult, snapshotInfo) <- accept(
        blocksForAcceptance,
        lastSnapshotContext,
        lastActiveTips,
        lastDeprecatedTips
      )
      _ <- CannotApplyBlocksError(acceptanceResult.notAccepted.map { case (_, reason) => reason })
        .raiseError[F, Unit]
        .whenA(acceptanceResult.notAccepted.nonEmpty)

    } yield snapshotInfo
    private val logger = Slf4jLogger.getLoggerFromClass(CurrencySnapshotConsensusFunctions.getClass)

    def getRequiredCollateral: Amount = collateral

    def consumeSignedMajorityArtifact(signedArtifact: Signed[CurrencyIncrementalSnapshot], context: CurrencySnapshotContext): F[Unit] =
      stateChannelSnapshotService.consume(signedArtifact, context)

    override def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastSignedArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent]
    ): F[(CurrencySnapshotArtifact, Set[CurrencySnapshotEvent])] = {

      val blocksForAcceptance = events
        .filter(_.height > lastSignedArtifact.height)
        .toList

      for {
        lastArtifactHash <- lastSignedArtifact.value.hashF
        currentOrdinal = lastSignedArtifact.ordinal.next
        lastActiveTips <- lastSignedArtifact.activeTips
        lastDeprecatedTips = lastSignedArtifact.tips.deprecated

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

        (height, subHeight) <- getHeightAndSubHeight(lastSignedArtifact, deprecated, remainedActive, accepted)

        returnedEvents = getReturnedEvents(acceptanceResult)
        stateProof <- CurrencySnapshotInfo.stateProof(snapshotInfo)

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
      acceptanceResult: BlockAcceptanceResult[CurrencyBlock]
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
