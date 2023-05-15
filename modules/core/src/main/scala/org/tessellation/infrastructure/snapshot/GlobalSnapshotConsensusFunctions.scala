package org.tessellation.infrastructure.snapshot

import cats.effect.Async
//import cats.syntax.either._
//import cats.syntax.flatMap._
//import cats.syntax.foldable._
//import cats.syntax.functor._
//import cats.syntax.functorFilter._
//import cats.syntax.option._
//import cats.syntax.order._
//import cats.syntax.validated._
import cats.syntax.all._
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.balance.Amount
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment.Mainnet
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot._
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput
import eu.timepit.refined.auto._
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.infrastructure.consensus.trigger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotConsensusFunctions[F[_]]
    extends ConsensusFunctions[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext]

object GlobalSnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Metrics](
    globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    environment: AppEnvironment,
    globalSnapshotCreator: GlobalSnapshotCreator[F],
    globalSnapshotValidator: GlobalSnapshotValidator[F]
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass(GlobalSnapshotConsensusFunctions.getClass)

    def consumeSignedMajorityArtifact(signedArtifact: Signed[GlobalSnapshotArtifact], context: GlobalSnapshotContext): F[Unit] =
      globalSnapshotStorage
        .prepend(signedArtifact, context)
        .ifM(
          metrics.globalSnapshot(signedArtifact),
          logger.error("Cannot save GlobalSnapshot into the storage")
        )

    def validateArtifact(
      lastSignedArtifact: Signed[GlobalSnapshotArtifact],
      lastContext: GlobalSnapshotContext,
      trigger: ConsensusTrigger,
      artifact: GlobalSnapshotArtifact
    ): F[Either[InvalidArtifact, (GlobalSnapshotArtifact, GlobalSnapshotContext)]] = {
      val expectedEpochProgress = trigger match {
        case EventTrigger => lastSignedArtifact.currencyData.epochProgress
        case TimeTrigger  => lastSignedArtifact.currencyData.epochProgress.next
      }

      globalSnapshotValidator
        .validateSnapshot(lastSignedArtifact, lastContext, artifact)
        .flatMap(
          _.toEither.leftTraverse { errNec =>
            logger.info(s"Artifact validation error ${errNec.show}").as(ArtifactMismatch)
          }.map {
            _.flatMap {
              case o @ (snapshot, _) =>
                if (snapshot.currencyData.epochProgress === expectedEpochProgress)
                  o.asRight
                else
                  ArtifactMismatch.asLeft
            }
          }
        )
    }

    def createProposalArtifact(
      lastKey: GlobalSnapshotKey,
      lastArtifact: Signed[GlobalSnapshotArtifact],
      snapshotContext: GlobalSnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[GlobalSnapshotEvent]
    ): F[(GlobalSnapshotArtifact, GlobalSnapshotContext, Set[GlobalSnapshotEvent])] = {
      val (scEvents: Set[StateChannelEvent], dagEvents: Set[DAGEvent]) = events.filter { event =>
        if (environment == Mainnet) event.isRight else true
      }.partitionMap(identity)

      val blocksForAcceptance = dagEvents
        .filter(_.height > lastArtifact.currencyData.height)

      val mintRewards = trigger match {
        case EventTrigger => false
        case TimeTrigger  => true
      }

      globalSnapshotCreator
        .createGlobalSnapshot(
          lastArtifact,
          snapshotContext,
          blocksForAcceptance,
          scEvents,
          mintRewards
        )
        .map { creationResult =>
          val returnedEvents = creationResult.awaitingBlocks.map(_.asRight[StateChannelEvent]) ++
            creationResult.awaitingStateChannelSnapshots.map(_.asLeft[DAGEvent])

          (
            creationResult.snapshot,
            creationResult.state,
            returnedEvents
          )
        }
    }

    object metrics {

      def globalSnapshot(signedGS: Signed[GlobalIncrementalSnapshot]): F[Unit] = {
        val activeTipsCount = signedGS.currencyData.tips.remainedActive.size + signedGS.currencyData.blocks.size
        val deprecatedTipsCount = signedGS.currencyData.tips.deprecated.size
        val transactionCount = signedGS.currencyData.blocks.map(_.block.transactions.size).sum
        val scSnapshotCount = signedGS.stateChannelSnapshots.view.values.map(_.size).sum

        Metrics[F].updateGauge("dag_global_snapshot_ordinal", signedGS.ordinal.value) >>
          Metrics[F].updateGauge("dag_global_snapshot_height", signedGS.currencyData.height.value) >>
          Metrics[F].updateGauge("dag_global_snapshot_signature_count", signedGS.proofs.size) >>
          Metrics[F]
            .updateGauge("dag_global_snapshot_tips_count", deprecatedTipsCount, Seq(("tip_type", "deprecated"))) >>
          Metrics[F].updateGauge("dag_global_snapshot_tips_count", activeTipsCount, Seq(("tip_type", "active"))) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_blocks_total", signedGS.currencyData.blocks.size) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_transactions_total", transactionCount) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_state_channel_snapshots_total", scSnapshotCount)
      }
    }

    def triggerPredicate(event: GlobalSnapshotEvent): Boolean = true

    override def facilitatorFilter(lastSignedArtifact: Signed[GlobalSnapshotArtifact], lastContext: GlobalSnapshotContext, peerId: peer.PeerId): F[Boolean] = ???
  }

}
