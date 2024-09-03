package io.constellationnetwork.node.shared.domain.event

import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hasher

trait EventCutter[F[_], A, B] {
  def cut(aEvents: List[A], bEvents: List[B], info: GlobalSnapshotInfo, ordinal: SnapshotOrdinal)(
    implicit hasher: Hasher[F]
  ): F[(List[A], List[B])]
}
