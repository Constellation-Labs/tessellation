package io.constellationnetwork.node.shared.domain.snapshot.programs

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.snapshot.Snapshot
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, HasherSelector}

trait Download[F[_], S <: Snapshot] {
  def download(implicit hasherSelector: HasherSelector[F]): F[Unit]

  /** Lightweight recovery download. Compared to `download`, this path may resync MptStore from the downloaded snapshot's checkpoint data
    * (dag-l0) or delegate to `download` (currency-l0). Layer-specific implementations decide which observe / cache-clear steps are needed;
    * dag-l0 still observes one round before rejoining, currency-l0 reuses the full path. Falls back to full download if local state is
    * missing or the gap exceeds threshold.
    */
  def recoveryDownload(implicit hasherSelector: HasherSelector[F]): F[Unit]

  def fetchSnapshot(hash: Option[Hash], ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Signed[S]]
}
