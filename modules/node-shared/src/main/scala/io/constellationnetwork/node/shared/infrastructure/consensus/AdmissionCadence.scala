package io.constellationnetwork.node.shared.infrastructure.consensus

/** Open-admission cadence: open expansion is considered only on rounds whose ordinal is a multiple of the expansion interval. */
object AdmissionCadence {

  def expansionAllowedAtOrdinal(ordinalValue: Long, expansionIntervalRounds: Int): Boolean =
    ordinalValue % math.max(1, expansionIntervalRounds).toLong == 0L
}
