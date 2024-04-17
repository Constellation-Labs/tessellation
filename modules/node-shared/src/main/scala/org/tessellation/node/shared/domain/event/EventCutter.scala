package org.tessellation.node.shared.domain.event

import org.tessellation.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import org.tessellation.security.Hasher

trait EventCutter[F[_], A, B] {
  def cut(aEvents: List[A], bEvents: List[B], info: GlobalSnapshotInfo, ordinal: SnapshotOrdinal)(
    implicit hasher: Hasher[F]
  ): F[(List[A], List[B])]
}
