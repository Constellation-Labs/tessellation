package io.constellationnetwork.node.shared.domain.snapshot.storage

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.snapshot.Snapshot
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

trait SnapshotStorage[F[_], S <: Snapshot, State] {

  def prepend(snapshot: Signed[S], state: State)(implicit hasher: Hasher[F]): F[Boolean]

  def head: F[Option[(Signed[S], State)]]
  def headSnapshot: F[Option[Signed[S]]]

  def get(ordinal: SnapshotOrdinal): F[Option[Signed[S]]]
  def getLastN(ordinal: SnapshotOrdinal, n: Int): F[Option[List[Signed[S]]]]

  def get(hash: Hash): F[Option[Signed[S]]]
  def getHash(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Option[Hash]]

}
