package org.tessellation.l0.domain.snapshot
import org.tessellation.schema.height.Height

trait SnapshotStorage[F[_]] {
  def storeSnapshot(snapshot: GlobalSnapshot): F[Unit]
  def getLastSnapshotHeight: F[Height]
}
