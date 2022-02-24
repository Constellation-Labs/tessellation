package org.tessellation.domain.snapshot

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}

trait GlobalSnapshotStorage[F[_]] {

  def prepend(snapshot: GlobalSnapshot): F[Boolean]

  def head: F[GlobalSnapshot]

  def get(ordinal: SnapshotOrdinal): F[Option[GlobalSnapshot]]

}
