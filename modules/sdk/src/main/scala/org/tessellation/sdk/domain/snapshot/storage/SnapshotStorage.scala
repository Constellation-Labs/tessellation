package org.tessellation.sdk.domain.snapshot.storage

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

trait SnapshotStorage[F[_], S <: Snapshot[_, _]] {

  def prepend(snapshot: Signed[S]): F[Boolean]

  def head: F[Option[Signed[S]]]

  def get(ordinal: SnapshotOrdinal): F[Option[Signed[S]]]

  def get(hash: Hash): F[Option[Signed[S]]]

}
