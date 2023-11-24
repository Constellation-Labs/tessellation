package org.tessellation.sdk.http.p2p.middleware

import cats.data.Kleisli
import cats.effect.Async

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.http.p2p.headers.`X-Id`

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
