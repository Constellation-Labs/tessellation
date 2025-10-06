package io.constellationnetwork.currency.dataApplication.context

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash

trait L1NodeContext[F[_]] {
  def getLastGlobalSnapshot: F[Option[Hashed[GlobalIncrementalSnapshot]]]
  def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]]
  def securityProvider: SecurityProvider[F]
  def getCurrencyId: F[CurrencyId]
}
