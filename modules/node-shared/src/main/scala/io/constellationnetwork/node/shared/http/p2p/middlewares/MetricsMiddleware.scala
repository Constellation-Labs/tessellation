package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.data.Kleisli
import cats.effect.kernel.Async
import cats.syntax.functor._
import eu.timepit.refined.auto._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import org.http4s.HttpRoutes
import org.http4s.headers.`X-Forwarded-For`

object MetricsMiddleware {
  def apply[F[_]: Async: Metrics](): HttpRoutes[F] => HttpRoutes[F] = { routes =>
    Kleisli { req =>
      routes(req).map { response =>
        val actualIp = req.remote.map(_.host.toString).getOrElse("unknown")
        val forwardedIp = req.headers
          .get[`X-Forwarded-For`]
          .map(_.values.head.toString.split(",").head.trim)
          .getOrElse("none")
        val tags = Seq(
          Metrics.unsafeLabelName("method") -> req.method.name,
          Metrics.unsafeLabelName("status") -> response.status.code.toString,
          Metrics.unsafeLabelName("actual_ip") -> actualIp,
          Metrics.unsafeLabelName("forwarded_ip") -> forwardedIp
        )

        val metricsKey: Metrics.MetricKey = "dag_http_requests_total"

        // Record metrics asynchronously without blocking the response
        Async[F].start(Metrics[F].incrementCounter(metricsKey, tags)).void

        response
      }
    }
  }
}
