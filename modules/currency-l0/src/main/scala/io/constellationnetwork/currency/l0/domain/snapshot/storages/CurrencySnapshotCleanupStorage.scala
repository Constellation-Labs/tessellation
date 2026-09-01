package io.constellationnetwork.currency.l0.domain.snapshot.storages

import io.constellationnetwork.schema._
import io.constellationnetwork.security.HasherSelector
import io.constellationnetwork.security.hash.Hash

trait CurrencySnapshotCleanupStorage[F[_]] {
  def cleanupAbove(ordinal: SnapshotOrdinal)(implicit hs: HasherSelector[F]): F[Unit]
  def cleanupCanonicalSuffix(ordinal: SnapshotOrdinal, anchorHash: Hash)(implicit hs: HasherSelector[F]): F[Unit]
}
