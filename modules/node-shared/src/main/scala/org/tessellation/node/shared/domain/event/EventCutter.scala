package org.tessellation.node.shared.domain.event

import org.tessellation.schema.GlobalSnapshotInfo

trait EventCutter[F[_], A, B] {
  def cut(aEvents: List[A], bEvents: List[B], info: GlobalSnapshotInfo): F[(List[A], List[B])]
}
