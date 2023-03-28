package org.tessellation.sdk.domain.snapshot.storage

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.height.Height
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.security.Hashed

trait LastSnapshotStorage[F[_], S <: Snapshot[_, _], SI <: SnapshotInfo[_]] {
  def set(snapshot: Hashed[S], state: SI): F[Unit]
  def setInitial(snapshot: Hashed[S], state: SI): F[Unit]
  def get: F[Option[Hashed[S]]]
  def getCombined: F[Option[(Hashed[S], SI)]]
  def getOrdinal: F[Option[SnapshotOrdinal]]
  def getHeight: F[Option[Height]]
}
