package org.tessellation.cutoff

import org.tessellation.schema.SnapshotOrdinal

trait OrdinalCutoff {
  def cutoff(cutoffOrdinal: SnapshotOrdinal, currentOrdinal: SnapshotOrdinal): Set[SnapshotOrdinal]
}
