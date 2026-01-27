package io.constellationnetwork.node.shared.http.routes

import cats.effect.Async
import cats.syntax.functor._

import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.routes.internal._

import eu.timepit.refined.auto._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.http4s.{HttpRoutes, MediaType}

final case class MetricRoutes[F[_]: Async: Metrics]() extends Http4sDsl[F] with PublicRoutes[F] {
  protected[routes] val prefixPath: InternalUrlPrefix = "/metrics"

  private val openMetricsContentType: `Content-Type` =
    `Content-Type`(MediaType.unsafeParse(Metrics.CONTENT_TYPE_OPENMETRICS_100))

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root =>
      Ok(Metrics[F].getAllAsText).map(_.withContentType(openMetricsContentType))
  }
}
