package io.constellationnetwork.currency.l0.snapshot

import cats.effect.Async
import cats.syntax.option._

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.SnapshotTimeoutsConfig
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.clients.SnapshotClient
import io.constellationnetwork.node.shared.http.p2p.middlewares.TimeoutMiddleware.withTimeout
import io.constellationnetwork.security.SecurityProvider

import org.http4s.client.Client

object CurrencySnapshotClient {
  type CurrencySnapshotClient[F[_]] = SnapshotClient[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    _client: Client[F],
    session: Session[F],
    snapshotTimeouts: SnapshotTimeoutsConfig
  ): CurrencySnapshotClient[F] =
    new SnapshotClient[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] {
      val client = withTimeout(_client, snapshotTimeouts.client)
      val optionalSession = session.some
      val urlPrefix = "snapshots"
    }
}
