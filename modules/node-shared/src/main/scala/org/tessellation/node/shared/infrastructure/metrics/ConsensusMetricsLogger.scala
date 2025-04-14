package org.tessellation.node.shared.infrastructure.metrics

import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusMetricsLogger[F[_]] {
  def logStateChannelSize(address: Address, dataSize: Long): F[Unit]
  def logEventCutterMetrics(scEventsCount: Int, dagEventsCount: Int, totalBinarySize: Long, processingTime: FiniteDuration): F[Unit]
  def logConsensusStageTransition(from: String, to: String, duration: FiniteDuration): F[Unit]
  def logTriggerProcessing(triggerType: String, processingTime: FiniteDuration): F[Unit]
  def logFacilitatorRoundMetrics(facilitators: Set[PeerId], roundDuration: FiniteDuration): F[Unit]
  def logLastSnapshotTime(key: String, currentTime: FiniteDuration): F[Unit]
}

object ConsensusMetricsLogger {
  def make[F[_]: Async: Metrics]: F[ConsensusMetricsLogger[F]] =
    Slf4jLogger.create[F].flatMap { logger =>
      Ref.of[F, Map[String, FiniteDuration]](Map.empty).map { lastSnapshotTimeRef =>
        new ConsensusMetricsLogger[F] {
          def logStateChannelSize(address: Address, dataSize: Long): F[Unit] =
            logger.debug(
              s"StateChannel size metrics: address=${address.show}, size=${dataSize} bytes"
            ) >>
              Metrics[F].updateGauge("dag_statechannel_size_bytes", dataSize, Seq(("address_id", address.show))) >>
              Metrics[F].recordDistribution("dag_statechannel_size_distribution", dataSize)

          def logEventCutterMetrics(
            scEventsCount: Int,
            dagEventsCount: Int,
            totalBinarySize: Long,
            processingTime: FiniteDuration
          ): F[Unit] =
            logger.debug(
              s"EventCutter metrics: scEvents=$scEventsCount, dagEvents=$dagEventsCount, " +
                s"totalSize=${totalBinarySize} bytes, processingTime=${processingTime.toMillis}ms"
            ) >>
              Metrics[F].updateGauge("dag_event_cutter_sc_events", scEventsCount) >>
              Metrics[F].updateGauge("dag_event_cutter_dag_events", dagEventsCount) >>
              Metrics[F].updateGauge("dag_event_cutter_total_size_bytes", totalBinarySize) >>
              Metrics[F].recordTime("dag_event_cutter_processing_time", processingTime)

          def logConsensusStageTransition(from: String, to: String, duration: FiniteDuration): F[Unit] =
            logger.debug(
              s"Consensus stage transition: $from -> $to, duration=${duration.toMillis}ms"
            ) >>
              Metrics[F].recordTime(
                "dag_consensus_stage_duration",
                duration,
                Seq(("stage_transition", s"${from}_to_${to}"))
              ) >>
              Metrics[F].incrementCounter("dag_consensus_stage_transitions_total", Seq(("from_state", from), ("to_state", to)))

          def logTriggerProcessing(triggerType: String, processingTime: FiniteDuration): F[Unit] =
            logger.debug(
              s"Consensus trigger processed: type=$triggerType, processingTime=${processingTime.toMillis}ms"
            ) >>
              Metrics[F].recordTime(
                "dag_consensus_trigger_processing_time",
                processingTime,
                Seq(("trigger_type", triggerType))
              ) >>
              Metrics[F].incrementCounter("dag_consensus_trigger_total", Seq(("trigger_type", triggerType)))

          def logFacilitatorRoundMetrics(facilitators: Set[PeerId], roundDuration: FiniteDuration): F[Unit] =
            logger.debug(
              s"Facilitator round metrics: facilitatorCount=${facilitators.size}, " +
                s"duration=${roundDuration.toMillis}ms, facilitators=${facilitators.mkString(",")}"
            ) >>
              Metrics[F].updateGauge("dag_facilitator_count", facilitators.size) >>
              Metrics[F].recordTime("dag_facilitator_round_duration", roundDuration) >>
              facilitators.toList.traverse_ { facilitator =>
                Metrics[F].incrementCounter("dag_facilitator_participation", Seq(("peer_id", facilitator.show.takeWhile(_ != '@'))))
              }

          // Maximum number of keys to keep in memory to prevent unbounded growth
          private val MAX_KEYS = 100

          def logLastSnapshotTime(key: String, currentTime: FiniteDuration): F[Unit] =
            lastSnapshotTimeRef.modify { lastTimes =>
              val previousTime = lastTimes.getOrElse(key, 0.nanos)
              val interval = if (previousTime > 0.nanos) currentTime - previousTime else 0.nanos

              // Ensure we don't accumulate an unbounded number of keys
              val updatedTimes =
                if (lastTimes.size >= MAX_KEYS && !lastTimes.contains(key)) {
                  // If we've hit the limit and this is a new key, remove the oldest key
                  lastTimes.tail.updated(key, currentTime)
                } else {
                  lastTimes.updated(key, currentTime)
                }

              (
                updatedTimes,
                logger.debug(s"Snapshot interval: key=$key, interval=${interval.toMillis}ms") >>
                  Metrics[F].recordTime("dag_snapshot_interval", interval) >>
                  Metrics[F].recordTime("dag_snapshot_interval_by_key", interval, Seq(("snapshot_key", key)))
              )
            }.flatten
        }
      }
    }
}
