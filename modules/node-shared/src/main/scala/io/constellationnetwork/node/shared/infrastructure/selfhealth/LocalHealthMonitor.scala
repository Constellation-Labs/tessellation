package io.constellationnetwork.node.shared.infrastructure.selfhealth

import java.lang.management.ManagementFactory

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Supervisor
import cats.syntax.all._

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import io.constellationnetwork.node.shared.config.types.LocalHealthMonitorConfig
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.unsafeLabelName

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Polls local JVM and OS health signals on a background fiber and exposes a `SelfHealthHint` for consensus to consult.
  *
  * Used in two paths:
  *   - Read directly when building a Facility declaration so the peer's current hint travels to other peers (Phase B /
  *     consensusSchemaVersion 15).
  *   - Exported to Prometheus for operator dashboards and alerting (Phase A, observational only).
  *
  * Signals (Phase A):
  *   - Max GC pause (across all collectors) observed in the configured history window (default 5 min). Source:
  *     `com.sun.management.GarbageCollectorMXBean.getLastGcInfo`.
  *   - Load1m / vCPU. Source: `OperatingSystemMXBean.getSystemLoadAverage` and `Runtime.availableProcessors`.
  *
  * Phase B adds the per-leader round-duration tail to the snapshot once `dag_consensus_round_completed_total` has accumulated enough
  * samples.
  */
trait LocalHealthMonitor[F[_]] {

  /** The current self-reported hint. Read by the Facility builder. */
  def current: F[SelfHealthHint]

  /** Full snapshot of signals + derived hint. For dashboards and `/node/info`. */
  def snapshot: F[HealthSnapshot]
}

object LocalHealthMonitor {

  /** Per-collector last-seen GC info id. `getId` is monotonic per collector so we can distinguish a fresh pause from a stale poll. */
  private case class GcSample(collectorName: String, lastSeenId: Long, durationMs: Long, observedAtMs: Long)

  /** Disabled instance for layers that don't wire LocalHealthMonitor. Always reports `Healthy`. */
  def disabled[F[_]: Async]: LocalHealthMonitor[F] = new LocalHealthMonitor[F] {
    private val empty = HealthSnapshot(
      hint = SelfHealthHint.Healthy,
      gcPauseMaxRecentMs = 0L,
      loadAvg1m = 0.0,
      vcpuCount = Runtime.getRuntime.availableProcessors(),
      loadPerVcpu = 0.0,
      recentLeaderRoundP95Ms = None,
      sampleCount = 0,
      overriddenByOperator = false
    )
    def current: F[SelfHealthHint] = Async[F].pure(SelfHealthHint.Healthy)
    def snapshot: F[HealthSnapshot] = Async[F].pure(empty)
  }

  def make[F[_]: Async: Metrics](
    config: LocalHealthMonitorConfig
  )(implicit supervisor: Supervisor[F]): F[LocalHealthMonitor[F]] = {
    val logger = Slf4jLogger.getLoggerFromName[F]("LocalHealthMonitor")
    val vcpuCount = Runtime.getRuntime.availableProcessors().max(1)

    val initial = HealthSnapshot(
      hint = config.operatorOverride.getOrElse(SelfHealthHint.Healthy),
      gcPauseMaxRecentMs = 0L,
      loadAvg1m = 0.0,
      vcpuCount = vcpuCount,
      loadPerVcpu = 0.0,
      recentLeaderRoundP95Ms = None,
      sampleCount = 0,
      overriddenByOperator = config.operatorOverride.isDefined
    )

    for {
      stateRef <- Ref.of[F, HealthSnapshot](initial)
      gcSamplesRef <- Ref.of[F, Vector[(Long, Long)]](Vector.empty)
      lastSeenGcIdsRef <- Ref.of[F, Map[String, Long]](Map.empty)
      _ <- emitMetrics(initial)
      _ <- supervisor.supervise(
        (Async[F].sleep(config.pollInterval) >>
          pollOnce(stateRef, gcSamplesRef, lastSeenGcIdsRef, config, vcpuCount, logger)).foreverM[Unit]
      )
    } yield
      new LocalHealthMonitor[F] {
        def current: F[SelfHealthHint] = stateRef.get.map(_.hint)
        def snapshot: F[HealthSnapshot] = stateRef.get
      }
  }

  private def pollOnce[F[_]: Async: Metrics](
    stateRef: Ref[F, HealthSnapshot],
    gcSamplesRef: Ref[F, Vector[(Long, Long)]],
    lastSeenGcIdsRef: Ref[F, Map[String, Long]],
    config: LocalHealthMonitorConfig,
    vcpuCount: Int,
    logger: org.typelevel.log4cats.Logger[F]
  ): F[Unit] = {
    val poll = for {
      now <- Async[F].realTime.map(_.toMillis)
      newGcEvents <- sampleGcEvents(lastSeenGcIdsRef, now)
      _ <- gcSamplesRef.update { existing =>
        val cutoff = now - config.historyWindow.toMillis
        (existing ++ newGcEvents).filter(_._1 >= cutoff)
      }
      samples <- gcSamplesRef.get
      gcPauseMax = samples.map(_._2).maxOption.getOrElse(0L)
      loadAvg1m <- Async[F].delay {
        val v = ManagementFactory.getOperatingSystemMXBean.getSystemLoadAverage
        if (v < 0.0) 0.0 else v
      }
      loadPerVcpu = if (vcpuCount > 0) loadAvg1m / vcpuCount.toDouble else 0.0
      derived = deriveHint(gcPauseMax, loadPerVcpu, config)
      effective = config.operatorOverride.getOrElse(derived)
      snap = HealthSnapshot(
        hint = effective,
        gcPauseMaxRecentMs = gcPauseMax,
        loadAvg1m = loadAvg1m,
        vcpuCount = vcpuCount,
        loadPerVcpu = loadPerVcpu,
        recentLeaderRoundP95Ms = None,
        sampleCount = samples.size,
        overriddenByOperator = config.operatorOverride.isDefined
      )
      _ <- stateRef.set(snap)
      _ <- emitMetrics(snap)
    } yield ()

    poll.handleErrorWith(e => logger.warn(e)("LocalHealthMonitor poll failed; will retry on next interval"))
  }

  private def sampleGcEvents[F[_]: Async](
    lastSeenGcIdsRef: Ref[F, Map[String, Long]],
    nowMs: Long
  ): F[Vector[(Long, Long)]] = Async[F].delay {
    val beans = ManagementFactory.getGarbageCollectorMXBeans.asScala.toList
    beans.collect {
      case sun: com.sun.management.GarbageCollectorMXBean =>
        Option(sun.getLastGcInfo).map(info => (sun.getName, info.getId, info.getDuration))
    }.flatten
  }.flatMap { observed =>
    lastSeenGcIdsRef.modify { lastSeen =>
      val newSamples = observed.collect {
        case (name, id, duration) if id > lastSeen.getOrElse(name, -1L) =>
          (nowMs, duration)
      }
      val updatedLastSeen = observed.foldLeft(lastSeen) {
        case (acc, (name, id, _)) => acc.updated(name, id.max(acc.getOrElse(name, -1L)))
      }
      (updatedLastSeen, newSamples.toVector)
    }
  }

  private def deriveHint(
    gcPauseMaxMs: Long,
    loadPerVcpu: Double,
    config: LocalHealthMonitorConfig
  ): SelfHealthHint = {
    val gcCritical = gcPauseMaxMs > config.gcPauseCriticalMs
    val loadCritical = loadPerVcpu > config.loadPerVcpuCritical
    val gcDegraded = gcPauseMaxMs > config.gcPauseDegradedMs
    val loadDegraded = loadPerVcpu > config.loadPerVcpuDegraded
    if (gcCritical || loadCritical) SelfHealthHint.Critical
    else if (gcDegraded || loadDegraded) SelfHealthHint.Degraded
    else SelfHealthHint.Healthy
  }

  private def emitMetrics[F[_]: Async: Metrics](snap: HealthSnapshot): F[Unit] = {
    val stateLabel = unsafeLabelName("state")
    val emitState: F[Unit] =
      SelfHealthHint.values.toList.traverse_ { v =>
        Metrics[F].updateGauge(
          "dag_node_self_health",
          if (v == snap.hint) 1.0 else 0.0,
          Seq(stateLabel -> v.entryName)
        )
      }
    val gcPauseSec = snap.gcPauseMaxRecentMs.toDouble / 1000.0
    emitState >>
      Metrics[F].updateGauge("dag_node_self_health_gc_pause_max_recent_seconds", gcPauseSec) >>
      Metrics[F].updateGauge("dag_node_self_health_load_per_vcpu", snap.loadPerVcpu) >>
      Metrics[F].updateGauge("dag_node_self_health_load_avg_1m", snap.loadAvg1m) >>
      Metrics[F].updateGauge("dag_node_self_health_overridden", if (snap.overriddenByOperator) 1.0 else 0.0)
  }
}
