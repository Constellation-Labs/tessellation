package org.tessellation.currency.l0.snapshot

import cats.effect.Async
import cats.syntax.option._

import org.tessellation.currency.schema.currency._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.cluster.services.Session
import org.tessellation.node.shared.http.p2p.clients.SnapshotClient
import org.tessellation.security.SecurityProvider

import org.http4s.client.Client

object CurrencySnapshotClient {
  type CurrencySnapshotClient[F[_]] = SnapshotClient[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]

  def make[F[_]: Async: SecurityProvider: KryoSerializer](_client: Client[F], session: Session[F]): CurrencySnapshotClient[F] =
    new SnapshotClient[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] {
      val client = _client
      val optionalSession = session.some
      val urlPrefix = "snapshots"
    }
}
