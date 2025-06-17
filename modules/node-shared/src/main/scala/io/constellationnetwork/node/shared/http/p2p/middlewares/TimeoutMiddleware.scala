package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.effect.Async
import cats.effect.implicits.genTemporalOps

import scala.concurrent.duration.FiniteDuration

import org.http4s.client.Client

object TimeoutMiddleware {
  def withTimeout[F[_]: Async](client: Client[F], timeout: FiniteDuration): Client[F] =
    Client { req =>
      client.run(req).timeout(timeout)
    }
}
