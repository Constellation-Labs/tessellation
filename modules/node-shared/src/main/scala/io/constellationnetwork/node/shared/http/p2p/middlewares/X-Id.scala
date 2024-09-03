package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.data.Kleisli
import cats.effect.Async

import io.constellationnetwork.node.shared.http.p2p.headers.`X-Id`
import io.constellationnetwork.schema.peer.PeerId

import org.http4s.{HttpRoutes, Request}

object `X-Id-Middleware` {

  def responseMiddleware[F[_]: Async](selfId: PeerId)(http: HttpRoutes[F]): HttpRoutes[F] =
    Kleisli { req: Request[F] =>
      http(req).map { res =>
        def headers = res.headers.put(`X-Id`(selfId))

        res.withHeaders(headers)
      }
    }

}
