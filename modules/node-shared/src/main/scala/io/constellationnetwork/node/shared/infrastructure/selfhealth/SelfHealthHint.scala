package io.constellationnetwork.node.shared.infrastructure.selfhealth

import derevo.derive

/** Snapshot of the underlying signals that drive `SelfHealthHint`. Surfaced via `LocalHealthMonitor.snapshot` for operator dashboards and
  * `/node/info` consumers; the `hint` field is what consensus rounds actually use.
  */
@derive(derevo.cats.show)
final case class HealthSnapshot(
  hint: SelfHealthHint,
  gcPauseMaxRecentMs: Long,
  loadAvg1m: Double,
  vcpuCount: Int,
  loadPerVcpu: Double,
  recentLeaderRoundP95Ms: Option[Long],
  sampleCount: Int,
  overriddenByOperator: Boolean,
  heapUsedRatio: Double
)
