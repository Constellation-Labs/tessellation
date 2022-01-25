package org.tessellation.l0.domain.snapshot

import org.tessellation.kernel.StateChannelSnapshot
import org.tessellation.l0.schema.snapshot.{GlobalSnapshot, GlobalSnapshotOrdinal}
import org.tessellation.schema.height.Height

trait SnapshotStorage[F[_]] {
  def storeSnapshot(snapshot: GlobalSnapshot): F[Unit]
  def getLastSnapshotHeight: F[Height]
  def getLastSnapshotSubHeight: F[Height]
  def getLastSnapshotOrdinal: F[GlobalSnapshotOrdinal]
  def getStateChannelSnapshots: F[Set[StateChannelSnapshot]]
}
