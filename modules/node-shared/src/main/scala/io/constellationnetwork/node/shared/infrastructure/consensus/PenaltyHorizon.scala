package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

import eu.timepit.refined.types.numeric.NonNegLong

/** Certificate-anchored penalty horizons (`penaltyUntil`).
  *
  * Absolute-ordinal penalties, never countdowns: an EvictionCertificate applied at ordinal N writes `target -> N + D`; an
  * AdmissionCertificate removes the target's entry; entries at or below the current key are dropped as expired. Every step is a pure map
  * update against consensus-agreed inputs, so a restart cannot observe a half-applied decrement.
  */
object PenaltyHorizon {

  def next(
    prior: SortedMap[PeerId, SnapshotOrdinal],
    certifiedEvictions: Set[PeerId],
    certifiedAdmissions: Set[PeerId],
    currentOrdinal: SnapshotOrdinal,
    penaltyDurationOrdinals: Int
  ): SortedMap[PeerId, SnapshotOrdinal] = {
    val duration = NonNegLong.from(penaltyDurationOrdinals.toLong).getOrElse(NonNegLong.MinValue)
    val until = currentOrdinal.plus(duration)
    val unexpired = prior.filter { case (_, ord) => ord.value.value > currentOrdinal.value.value }
    val withEvictions = certifiedEvictions.foldLeft(unexpired)((acc, pid) => acc.updated(pid, until))

    withEvictions -- certifiedAdmissions
  }
}
