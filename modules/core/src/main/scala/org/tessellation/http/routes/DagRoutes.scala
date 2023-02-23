package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.applicative._

import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Location
import org.http4s.server.Router

final case class DagRoutes[F[_]: Async]() extends Http4sDsl[F] {
  private[routes] val prefixPath = "dag"
  private[routes] val redirectPrefixPath = "currency"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ _ =>
      println(req.uri.path)
      redirectResponse(req)
        .map(_.pure[F])
        .getOrElse(NotFound())
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

  private def redirectResponse(req: Request[F]): Option[Response[F]] = {
    val segments = req.uri.path.segments

    segments.zipWithIndex.find {
      case (s, _) => s == Uri.Path.Segment(prefixPath)
    }.map {
      case (_, i) => segments.updated(i, Uri.Path.Segment(redirectPrefixPath))
    }
      .map(Path(_).toAbsolute)
      .map(path => Location(Uri(path = path)))
      .map {
        Response[F]()
          .withStatus(Status.PermanentRedirect)
          .withHeaders(_)
      }
  }

}
