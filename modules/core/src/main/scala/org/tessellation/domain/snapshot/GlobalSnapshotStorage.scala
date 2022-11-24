package org.tessellation.domain.snapshot

import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

trait GlobalSnapshotStorage[F[_]] {

  def prepend(snapshot: Signed[GlobalSnapshot]): F[Boolean]

  def head: F[Option[Signed[GlobalSnapshot]]]

  def get(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]]

  def get(hash: Hash): F[Option[Signed[GlobalSnapshot]]]

}
