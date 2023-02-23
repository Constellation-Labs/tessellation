package org.tessellation.currency.infrastructure.snapshot

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._

import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.height.Height
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import org.tessellation.sdk.infrastructure.snapshot.{InvalidHeight, NoTipsRemaining, SnapshotConsensusFunctions}
import org.typelevel.log4cats.slf4j.Slf4jLogger
abstract class CurrencySnapshotConsensusFunctions[F[_]: Async: SecurityProvider]
    extends SnapshotConsensusFunctions[
      F,
      CurrencyTransaction,
      CurrencyBlock,
      CurrencySnapshotEvent,
      CurrencySnapshotArtifact,
      ConsensusTrigger
    ] {}

object CurrencySnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Metrics](
    snapshotStorage: SnapshotStorage[F, CurrencySnapshot],
    blockAcceptanceManager: BlockAcceptanceManager[F, CurrencyTransaction, CurrencyBlock],
    collateral: Amount,
    environment: AppEnvironment
  ): CurrencySnapshotConsensusFunctions[F] = new CurrencySnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass(CurrencySnapshotConsensusFunctions.getClass)

    def getRequiredCollateral: Amount = collateral

    def consumeSignedMajorityArtifact(signedArtifact: Signed[CurrencySnapshot]): F[Unit] =
      snapshotStorage
        .prepend(signedArtifact)
        .ifM(
          Async[F].unit,
          logger.error("Cannot save CurrencySnapshot into the storage")
        )

    def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
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

        tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
        context = BlockAcceptanceContext.fromStaticData(
          lastArtifact.info.balances,
          lastArtifact.info.lastTxRefs,
          tipUsages,
          collateral
        )
        acceptanceResult <- blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)

        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )

        height <- getHeight(lastArtifact, deprecated, remainedActive, accepted)

        updatedLastTxRefs = lastArtifact.info.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs
        balances = lastArtifact.info.balances ++ acceptanceResult.contextUpdate.balances
        positiveBalances = balances.filter { case (_, balance) => balance =!= Balance.empty }

        returnedEvents = getReturnedEvents(acceptanceResult)

        artifact = CurrencySnapshot(
          currentOrdinal,
          height,
          lastArtifactHash,
          accepted,
          SnapshotTips(
            deprecated = deprecated,
            remainedActive = remainedActive
          ),
          CurrencySnapshotInfo(
            updatedLastTxRefs,
            positiveBalances
          )
        )
      } yield (artifact, returnedEvents)
    }

    private def getHeight(
      lastGS: CurrencySnapshot,
      deprecated: Set[DeprecatedTip],
      remainedActive: Set[ActiveTip],
      accepted: Set[BlockAsActiveTip[CurrencyBlock]]
    ): F[Height] = {
      val tipHeights = (deprecated.map(_.block.height) ++ remainedActive.map(_.block.height) ++ accepted
        .map(_.block.height)).toList

      for {
        height <- tipHeights.minimumOption.liftTo[F](NoTipsRemaining)

        _ <-
          if (height < lastGS.height)
            InvalidHeight(lastGS.height, height).raiseError
          else
            Applicative[F].unit

      } yield height
    }

    private def getReturnedEvents(
      acceptanceResult: BlockAcceptanceResult[CurrencyBlock]
    ): Set[CurrencySnapshotEvent] =
      acceptanceResult.notAccepted.mapFilter {
        case (signedBlock, _: BlockAwaitReason) => signedBlock.some
        case _                                  => none
      }.toSet
  }
}
