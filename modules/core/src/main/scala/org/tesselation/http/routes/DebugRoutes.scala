package org.tesselation.http.routes

import cats.Monad

import org.tesselation.modules.Services

import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class DebugRoutes[F[_]: Monad](
  services: Services[F]
) extends Http4sDsl[F] {

  private[routes] val prefixPath = "/debug"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root => Ok()
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
