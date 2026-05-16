package io.constellationnetwork.node.shared.infrastructure.selfhealth

import cats.{Eq, Order, Show}

import scala.util.Try

import derevo.derive
import enumeratum._
import io.circe._

/** A peer's self-reported indication of whether its local environment (JVM / OS) is currently healthy enough to lead a consensus round.
  *
  * Three discrete states so the boundary is observable as a Prometheus label and so operator alerts can fire on exact transitions:
  *
  *   - `Healthy`: no signal exceeds the configured `Degraded` threshold. Eligible for tier 0 in `selectLeaderWeighted` provided the
  *     existing completion-ratio gate passes.
  *   - `Degraded`: at least one signal exceeded the `Degraded` threshold (e.g. GC pause max > 5s in last 5 min, load1m / vCPU > 3.0). The
  *     peer should not be picked as leader if any `Healthy` peer is available, but stays in the facilitator/witness pools.
  *   - `Critical`: at least one signal exceeded the `Critical` threshold (e.g. GC pause > 30s, load1m / vCPU > 6.0). The peer is selected
  *     as leader only when no `Healthy` or `Degraded` peer is available (avoids the all-Critical-cluster deadlock).
  *
  * Self-reported, not externally verified. The adversarial path (a peer lying "Healthy") is rendered safe by the existing v14
  * completion-ratio ratchet: a lying peer's `peer_quality` ratio decays as its rounds abandon, and it falls into tier 1 anyway after ~10-20
  * rounds.
  */
sealed trait SelfHealthHint extends EnumEntry {
  def label: String = entryName
}

object SelfHealthHint extends Enum[SelfHealthHint] with SelfHealthHintCodecs {
  val values = findValues

  case object Healthy extends SelfHealthHint { override val entryName = "healthy" }
  case object Degraded extends SelfHealthHint { override val entryName = "degraded" }
  case object Critical extends SelfHealthHint { override val entryName = "critical" }

  implicit val show: Show[SelfHealthHint] = Show.show(_.entryName)
  implicit val eq: Eq[SelfHealthHint] = Eq.fromUniversalEquals

  /** Order: Healthy < Degraded < Critical. Useful when merging or comparing hints across peers. */
  implicit val order: Order[SelfHealthHint] = Order.by {
    case Healthy  => 0
    case Degraded => 1
    case Critical => 2
  }
  implicit val ordering: Ordering[SelfHealthHint] = order.toOrdering
}

trait SelfHealthHintCodecs {
  implicit val encode: Encoder[SelfHealthHint] = Encoder.encodeString.contramap(_.entryName)
  implicit val decode: Decoder[SelfHealthHint] = Decoder.decodeString.emapTry(s => Try(SelfHealthHint.withName(s)))
}

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
