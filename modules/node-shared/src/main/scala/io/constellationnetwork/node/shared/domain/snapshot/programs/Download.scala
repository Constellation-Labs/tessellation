package io.constellationnetwork.node.shared.domain.snapshot.programs

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.snapshot.Snapshot
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, HasherSelector}

trait Download[F[_], S <: Snapshot] {
  def download(implicit hasherSelector: HasherSelector[F]): F[Unit]
  def fetchSnapshot(hash: Option[Hash], ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Signed[S]]
}
