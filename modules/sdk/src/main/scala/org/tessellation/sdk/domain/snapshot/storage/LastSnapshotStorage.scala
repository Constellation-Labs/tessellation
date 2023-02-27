package org.tessellation.sdk.domain.snapshot.storage

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.height.Height
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.security.Hashed

trait LastSnapshotStorage[F[_], S <: Snapshot[_, _]] {
  def set(snapshot: Hashed[S]): F[Unit]
  def setInitial(snapshot: Hashed[S]): F[Unit]
  def get: F[Option[Hashed[S]]]
  def getOrdinal: F[Option[SnapshotOrdinal]]
  def getHeight: F[Option[Height]]
}
