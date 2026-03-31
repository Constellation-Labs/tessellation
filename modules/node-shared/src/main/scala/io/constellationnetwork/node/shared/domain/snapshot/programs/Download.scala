package io.constellationnetwork.node.shared.domain.snapshot.programs

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.snapshot.Snapshot
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, HasherSelector}

trait Download[F[_], S <: Snapshot] {
  def download(implicit hasherSelector: HasherSelector[F]): F[Unit]

  /** Lightweight recovery download that skips cache clearing and the observe phase. Used when a node has local persisted state and only
    * needs to catch up a small gap. Falls back to full download if local state is missing or gap exceeds threshold.
    */
  def recoveryDownload(implicit hasherSelector: HasherSelector[F]): F[Unit]

  def fetchSnapshot(hash: Option[Hash], ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Signed[S]]
}
