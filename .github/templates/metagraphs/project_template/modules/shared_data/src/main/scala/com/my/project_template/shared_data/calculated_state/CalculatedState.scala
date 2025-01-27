package com.my.project_template.shared_data.calculated_state

import io.constellationnetwork.schema.SnapshotOrdinal

import com.my.project_template.shared_data.types.Types.UsageUpdateCalculatedState

case class CalculatedState(ordinal: SnapshotOrdinal, state: UsageUpdateCalculatedState)

object CalculatedState {
  def empty: CalculatedState =
    CalculatedState(
      SnapshotOrdinal.MinValue,
      UsageUpdateCalculatedState(Map.empty)
    )

}
