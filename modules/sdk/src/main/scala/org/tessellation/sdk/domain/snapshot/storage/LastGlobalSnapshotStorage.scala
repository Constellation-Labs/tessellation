package org.tessellation.sdk.domain.snapshot.storage

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.schema.height.Height
import org.tessellation.security.Hashed

trait LastGlobalSnapshotStorage[F[_]] {
  def set(snapshot: Hashed[GlobalSnapshot]): F[Unit]
  def setInitial(snapshot: Hashed[GlobalSnapshot]): F[Unit]
  def get: F[Option[Hashed[GlobalSnapshot]]]
  def getOrdinal: F[Option[SnapshotOrdinal]]
  def getHeight: F[Option[Height]]
}
