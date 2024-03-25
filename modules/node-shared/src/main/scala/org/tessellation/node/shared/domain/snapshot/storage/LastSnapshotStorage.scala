package org.tessellation.node.shared.domain.snapshot.storage

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.height.Height
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.security.Hashed

import fs2.Stream

trait LastSnapshotStorage[F[_], S <: Snapshot, SI <: SnapshotInfo[_]] {
  def set(snapshot: Hashed[S], state: SI): F[Unit]
  def setInitial(snapshot: Hashed[S], state: SI): F[Unit]
  def get: F[Option[Hashed[S]]]
  def getCombined: F[Option[(Hashed[S], SI)]]
  def getCombinedStream: Stream[F, Option[(Hashed[S], SI)]]
  def getOrdinal: F[Option[SnapshotOrdinal]]
  def getHeight: F[Option[Height]]
}
