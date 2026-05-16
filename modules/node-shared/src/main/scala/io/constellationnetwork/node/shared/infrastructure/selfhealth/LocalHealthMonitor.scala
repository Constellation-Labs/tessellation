package io.constellationnetwork.node.shared.infrastructure.selfhealth

import java.lang.management.{ManagementFactory, MemoryUsage}

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Supervisor
import cats.syntax.all._

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
  *     `com.sun.management.GarbageCollectorMXBean.getLastGcInfo.getDuration`. For mostly-concurrent collectors (ZGC, Shenandoah)
  *     `getDuration` is the cycle wall time including concurrent phases, not the STW pause, so it is suppressed (sampled as 0) for those
  *     collectors; operators on ZGC should monitor `jvm_gc_pause_seconds_count{cause="Allocation Stall"}` from Micrometer for the real
  *     degradation signal.
  *   - Load1m / vCPU. Source: `OperatingSystemMXBean.getSystemLoadAverage` and `Runtime.availableProcessors`.
  *   - Heap used / max (observational only; ZGC operates near-full by design so this does not feed `deriveHint`).
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
      overriddenByOperator = false,
      heapUsedRatio = 0.0
    )
    def current: F[SelfHealthHint] = Async[F].pure(SelfHealthHint.Healthy)
    def snapshot: F[HealthSnapshot] = Async[F].pure(empty)
  }

  def make[F[_]: Async: Metrics](
    config: LocalHealthMonitorConfig
  )(implicit supervisor: Supervisor[F]): F[LocalHealthMonitor[F]] = {
    val logger = Slf4jLogger.getLoggerFromName[F]("LocalHealthMonitor")
    val vcpuCount = Runtime.getRuntime.availableProcessors().max(1)
    val collectorNames = activeCollectorNames()

    val initial = HealthSnapshot(
      hint = config.operatorOverride.getOrElse(SelfHealthHint.Healthy),
      gcPauseMaxRecentMs = 0L,
      loadAvg1m = 0.0,
      vcpuCount = vcpuCount,
      loadPerVcpu = 0.0,
      recentLeaderRoundP95Ms = None,
      sampleCount = 0,
      overriddenByOperator = config.operatorOverride.isDefined,
      heapUsedRatio = 0.0
    )

    for {
      stateRef <- Ref.of[F, HealthSnapshot](initial)
      gcSamplesRef <- Ref.of[F, Vector[(Long, Long)]](Vector.empty)
      lastSeenGcIdsRef <- Ref.of[F, Map[String, Long]](Map.empty)
      _ <- logger.info(
        s"LocalHealthMonitor starting; vcpu=$vcpuCount collectors=[${collectorNames.mkString(",")}]" +
          (if (isMostlyConcurrent(collectorNames))
             "; concurrent GC detected, getDuration()-based pause signal suppressed (ZGC/Shenandoah STW pauses are sub-ms by design)"
           else "")
      )
      _ <- emitCollectorInfo(collectorNames)
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

  private def activeCollectorNames(): List[String] =
    ManagementFactory.getGarbageCollectorMXBeans.asScala.map(_.getName).toList

  /** Returns true if every active collector is a mostly-concurrent one (ZGC, Shenandoah). When true, `getDuration()`-derived "pause" times
    * are cycle wall times, not STW pauses, and must not feed the Degraded/Critical thresholds.
    */
  private def isMostlyConcurrent(names: List[String]): Boolean =
    names.nonEmpty && names.forall(isConcurrentCollectorName)

  private def isConcurrentCollectorName(name: String): Boolean =
    name.startsWith("ZGC") || name.contains("Shenandoah")

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
      heapUsedRatio <- Async[F].delay(sampleHeapUsedRatio())
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
        overriddenByOperator = config.operatorOverride.isDefined,
        heapUsedRatio = heapUsedRatio
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
        Option(sun.getLastGcInfo).map { info =>
          val name = sun.getName
          // For mostly-concurrent collectors (ZGC, Shenandoah) `GcInfo.getDuration()` is the cycle's wall-clock time, NOT the
          // stop-the-world pause portion. ZGC's STW pauses are bounded to sub-millisecond by design, so the cycle duration
          // (often seconds during high allocation rate) is the wrong signal for the gcPause* thresholds. Treat as 0 here;
          // operator dashboards can still see Allocation Stall counts via Micrometer's `jvm_gc_pause_seconds_count{cause="Allocation Stall"}`.
          val durationMs = if (isConcurrentCollectorName(name)) 0L else info.getDuration
          (name, info.getId, durationMs)
        }
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

  private def sampleHeapUsedRatio(): Double = {
    val usage: MemoryUsage = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
    val max = usage.getMax
    if (max > 0L) usage.getUsed.toDouble / max.toDouble else 0.0
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
      Metrics[F].updateGauge("dag_node_self_health_overridden", if (snap.overriddenByOperator) 1.0 else 0.0) >>
      Metrics[F].updateGauge("dag_node_self_health_heap_used_ratio", snap.heapUsedRatio)
  }

  /** Info gauge that records the active GC collector names so operators can confirm whether ZGC vs G1 is running on each peer. Always 1.0;
    * the value carries the name in a label.
    */
  private def emitCollectorInfo[F[_]: Async: Metrics](collectorNames: List[String]): F[Unit] = {
    val collectorLabel = unsafeLabelName("collector")
    collectorNames.traverse_ { name =>
      Metrics[F].updateGauge("dag_node_self_health_gc_collector_info", 1.0, Seq(collectorLabel -> name))
    }
  }
}
