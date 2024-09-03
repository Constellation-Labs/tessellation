package io.constellationnetwork.cutoff

import io.constellationnetwork.schema.SnapshotOrdinal

trait OrdinalCutoff {
  def cutoff(cutoffOrdinal: SnapshotOrdinal, currentOrdinal: SnapshotOrdinal): Set[SnapshotOrdinal]
}
