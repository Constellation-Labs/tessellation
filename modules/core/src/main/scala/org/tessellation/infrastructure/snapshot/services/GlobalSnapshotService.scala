package org.tessellation.infrastructure.snapshot.services

import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._

import org.tessellation.sdk.domain.consensus.ArtifactService
import org.tessellation.security.signature.Signed
import org.tessellation.infrastructure.snapshot._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import cats.effect.Async
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.infrastructure.snapshot._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.infrastructure.metrics.Metrics

trait GlobalSnapshotService[F[_]] extends ArtifactService[F, GlobalSnapshotArtifact, GlobalSnapshotContext] {

  def consume(signedArtifact: Signed[GlobalSnapshotArtifact], context: GlobalSnapshotContext): F[Unit]
}

object GlobalSnapshotService {
  def make[F[_]: Async: KryoSerializer: Metrics](
    globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext]
  ): GlobalSnapshotService[F] = 
    new GlobalSnapshotService[F] {
      private val logger = Slf4jLogger.getLogger

      def consume(signedArtifact: Signed[GlobalSnapshotArtifact], context: GlobalSnapshotContext): F[Unit] =
        globalSnapshotStorage
          .prepend(signedArtifact, context)
          .ifM(
            metrics.globalSnapshot(signedArtifact),
            logger.error("Cannot save GlobalSnapshot into the storage")
          )

    object metrics {

      def globalSnapshot(signedGS: Signed[GlobalIncrementalSnapshot]): F[Unit] = {
        val activeTipsCount = signedGS.tips.remainedActive.size + signedGS.blocks.size
        val deprecatedTipsCount = signedGS.tips.deprecated.size
        val transactionCount = signedGS.blocks.map(_.block.transactions.size).sum
        val scSnapshotCount = signedGS.stateChannelSnapshots.view.values.map(_.size).sum

        Metrics[F].updateGauge("dag_global_snapshot_ordinal", signedGS.ordinal.value) >>
          Metrics[F].updateGauge("dag_global_snapshot_height", signedGS.height.value) >>
          Metrics[F].updateGauge("dag_global_snapshot_signature_count", signedGS.proofs.size) >>
          Metrics[F]
            .updateGauge("dag_global_snapshot_tips_count", deprecatedTipsCount, Seq(("tip_type", "deprecated"))) >>
          Metrics[F].updateGauge("dag_global_snapshot_tips_count", activeTipsCount, Seq(("tip_type", "active"))) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_blocks_total", signedGS.blocks.size) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_transactions_total", transactionCount) >>
          Metrics[F].incrementCounterBy("dag_global_snapshot_state_channel_snapshots_total", scSnapshotCount)
      }
    }
    }
}
