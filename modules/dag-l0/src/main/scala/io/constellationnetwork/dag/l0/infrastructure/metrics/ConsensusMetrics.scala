package io.constellationnetwork.dag.l0.infrastructure.metrics

import cats.effect.Sync
import cats.effect.kernel.Clock
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._

object ConsensusMetrics {

  /** Records the start of a consensus round with the given key and events count.
    */
  def recordConsensusStart[F[_]: Sync: Metrics: Clock](
    key: SnapshotOrdinal,
    eventsCount: Int
  ): F[Unit] =
    Metrics[F].incrementCounter("dag_consensus_started_total") *>
      Metrics[F].updateGauge("dag_consensus_events_count", eventsCount.toDouble)

  /** Records the time spent in facilities collection stage.
    */
  def recordFacilitiesCollectionTime[F[_]: Sync: Metrics](duration: FiniteDuration): F[Unit] =
    Metrics[F].recordTime("dag_consensus_facilities_collection_time_seconds", duration)

  /** Records the time spent in proposals collection stage.
    */
  def recordProposalsCollectionTime[F[_]: Sync: Metrics](duration: FiniteDuration): F[Unit] =
    Metrics[F].recordTime("dag_consensus_proposals_collection_time_seconds", duration)

  /** Records the time spent in signatures collection stage.
    */
  def recordSignaturesCollectionTime[F[_]: Sync: Metrics](duration: FiniteDuration): F[Unit] =
    Metrics[F].recordTime("dag_consensus_signatures_collection_time_seconds", duration)

  /** Records when all events are received.
    */
  def recordAllEventsReceived[F[_]: Sync: Metrics: Clock](): F[Unit] =
    Metrics[F].incrementCounter("dag_consensus_all_events_received_total")

  /** Records the number of facilitators participating in the consensus.
    */
  def recordFacilitatorsCount[F[_]: Sync: Metrics](count: Int): F[Unit] =
    Metrics[F].updateGauge("dag_consensus_facilitators_count", count.toDouble)

  /** Records when all declarations are received.
    */
  def recordAllDeclarationsReceived[F[_]: Sync: Metrics: Clock](): F[Unit] =
    Metrics[F].incrementCounter("dag_consensus_all_declarations_received_total")

  /** Records the total time spent in consensus.
    */
  def recordTotalConsensusTime[F[_]: Sync: Metrics](duration: FiniteDuration): F[Unit] =
    Metrics[F].recordTime("dag_consensus_total_time_seconds", duration)

  /** Records the latency of a peer in a specific stage.
    */
  def recordPeerLatency[F[_]: Sync: Metrics](
    peerId: PeerId,
    stage: String,
    latency: FiniteDuration
  ): F[Unit] =
    Metrics[F].recordTime(
      "dag_consensus_peer_latency_seconds",
      latency,
      Seq(("peer_id", peerId.show), ("stage", stage))
    )

  /** Records a stage stall detection.
    */
  def recordStageStall[F[_]: Sync: Metrics](stage: String, ordinal: SnapshotOrdinal): F[Unit] =
    Metrics[F].incrementCounter("dag_consensus_stage_stall_total", Seq(("stage", stage), ("ordinal", ordinal.show)))

  /** Records the percentage of events received and the time elapsed.
    */
  def recordEventReceiptPercentage[F[_]: Sync: Metrics](percentReceived: Double, elapsedMillis: Long): F[Unit] =
    Metrics[F].updateGauge("dag_consensus_event_receipt_percentage", percentReceived) *>
      Metrics[F].updateGauge("dag_consensus_event_receipt_elapsed_ms", elapsedMillis.toDouble)

  /** Records the size of a consensus artifact.
    */
  def recordArtifactSize[F[_]: Sync: Metrics](
    artifact: Signed[GlobalIncrementalSnapshot],
    sizeInMB: Double
  ): F[Unit] =
    Metrics[F].updateGauge(
      "dag_consensus_artifact_size_mb",
      sizeInMB,
      Seq(("ordinal", artifact.ordinal.show))
    )
}
