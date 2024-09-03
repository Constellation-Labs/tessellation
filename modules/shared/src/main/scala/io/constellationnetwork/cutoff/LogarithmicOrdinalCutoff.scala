package io.constellationnetwork.cutoff

import io.constellationnetwork.schema.SnapshotOrdinal

object LogarithmicOrdinalCutoff {
  def make: OrdinalCutoff = new OrdinalCutoff {
    def cutoff(cutoffOrdinal: SnapshotOrdinal, currentOrdinal: SnapshotOrdinal): Set[SnapshotOrdinal] = {
      val lastOrdinal = currentOrdinal.value.value

      /* NOTE:
       * To determine the necessary number of layers, Math.log10 should be used, although it necessitates a Double as its input type.
       *   Math.log10(lastOrdinal) + 1
       * Converting SnapshotOrdinal to Double may result in precision loss for substantial values.
       * Given that SnapshotOrdinal is a non-negative Long, assesing the number of digits is sufficient to ascertain the number of layers.
       */
      val layers = lastOrdinal.toString.length

      val (ordinalsToKeep, _) = (0 until layers)
        .foldLeft((Set.empty[Long], lastOrdinal)) {
          case ((acc, lastOrdinal), layer) =>
            val dividend = Math.pow(10, layer.toDouble).toInt

            val startRange = Math.max(0, lastOrdinal - Math.pow(10, layer.toDouble + 1).toInt + 1)

            val endRange = lastOrdinal / dividend
            val ordinalsForLayer = (startRange / dividend to endRange)
              .map(_ * dividend)

            (acc.concat(ordinalsForLayer), startRange)
        }

      ordinalsToKeep
        .filter(_ >= cutoffOrdinal.value.value)
        .map(SnapshotOrdinal.unsafeApply)
    }
  }
}
