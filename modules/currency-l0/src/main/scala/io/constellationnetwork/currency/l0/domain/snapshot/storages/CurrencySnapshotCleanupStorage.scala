package io.constellationnetwork.currency.l0.domain.snapshot.storages

import io.constellationnetwork.schema._
import io.constellationnetwork.security.HasherSelector

trait CurrencySnapshotCleanupStorage[F[_]] {
  def cleanupAbove(ordinal: SnapshotOrdinal)(implicit hs: HasherSelector[F]): F[Unit]
}
