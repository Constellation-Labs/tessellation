package org.tessellation.node.shared.domain.snapshot.storage

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.security.Hasher
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

trait SnapshotStorage[F[_], S <: Snapshot, State] {

  def prepend(snapshot: Signed[S], state: State)(implicit hasher: Hasher[F]): F[Boolean]

  def head: F[Option[(Signed[S], State)]]
  def headSnapshot: F[Option[Signed[S]]]

  def get(ordinal: SnapshotOrdinal): F[Option[Signed[S]]]

  def get(hash: Hash): F[Option[Signed[S]]]
  def getHash(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Option[Hash]]

}
