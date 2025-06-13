package io.constellationnetwork.node.shared.http.p2p.middlewares

import cats.data.Kleisli
import cats.effect.kernel.Async
import cats.syntax.all._
import eu.timepit.refined.auto._
import eu.timepit.refined.api.Refined
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import org.http4s.HttpRoutes
import org.http4s.headers.`X-Forwarded-For`
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

object MetricsMiddleware {
  
  // Helper to create MetricKey safely
  private def unsafeMetricKey(s: String): Metrics.MetricKey = Refined.unsafeApply(s)

  def apply[F[_]: Async: Metrics](): HttpRoutes[F] => HttpRoutes[F] = { routes =>
    Kleisli { req =>
      val startTime = System.nanoTime()
      routes(req).semiflatMap { response =>
        val scriptName = req.scriptName.renderString
        val endTime = System.nanoTime()
        val duration = FiniteDuration(endTime - startTime, TimeUnit.NANOSECONDS)
        // Extract route path and normalize it for metric naming
        val routePath = normalizeRoutePath(req.pathInfo.renderString)
        val method = req.method.name.toLowerCase
        val actualIp = req.remote.map(_.host.toString).getOrElse("unknown")
        val forwardedIp = req.headers
          .get[`X-Forwarded-For`]
          .map(_.values.head.toString.split(",").head.trim)
          .getOrElse("none")

        val commonTags = Seq(
          Metrics.unsafeLabelName("script_name") -> scriptName,
          Metrics.unsafeLabelName("method") -> req.method.name,
          Metrics.unsafeLabelName("status") -> response.status.code.toString,
          Metrics.unsafeLabelName("route") -> routePath,
          Metrics.unsafeLabelName("status_class") -> s"${response.status.code / 100}xx"
        )

        val ipTags = Seq(
          Metrics.unsafeLabelName("actual_ip") -> actualIp,
          Metrics.unsafeLabelName("forwarded_ip") -> forwardedIp
        )
        val tags = commonTags ++ ipTags

        // Generic HTTP metrics with route as label
        val durationMetricKey: Metrics.MetricKey = unsafeMetricKey("dag_http_request_time")
        val requestSizeMetricKey: Metrics.MetricKey = unsafeMetricKey("dag_http_request_size")
        val responseSizeMetricKey: Metrics.MetricKey = unsafeMetricKey("dag_http_response_size")

        // Record metrics asynchronously without blocking the response
        val metricsRecording = for {

          _ <- Metrics[F].recordTimeHistogram(durationMetricKey, duration, tags)
          // 4. Request size histograms (both route-specific and generic)

          _ <- req.contentLength.traverse_ { size =>
              Metrics[F].recordSizeHistogram(requestSizeMetricKey, size, tags)
          }
          // 5. Response size histograms (both route-specific and generic)
          _ <- response.contentLength.traverse_ { size =>
            Metrics[F].recordSizeHistogram(responseSizeMetricKey, size, tags)
          }


        } yield ()
        Async[F].start(metricsRecording) >> response.pure[F]
      }
    }
  }
  
  /**
   * Normalize route path for use in metric names
   * Examples:
   * - "/api/v1/users/123" -> "api_v1_users_id"  
   * - "/health" -> "health"
   * - "/metrics" -> "metrics"
   * - "/" -> "root"
   */
  private def normalizeRoutePath(path: String): String = {
    val cleaned = path
      .stripPrefix("/")
      .stripSuffix("/")
      
    if (cleaned.isEmpty) {
      "root"
    } else {
      cleaned
        .split("/")
        .map { segment =>
          // Replace numeric IDs and UUIDs with generic placeholders
          if (segment.matches("\\d+")) {
            "id"
          } else if (segment.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            "uuid"
          } else if (segment.matches("[0-9a-fA-F]{64}")) {
            "hash"
          } else {
            // Replace non-alphanumeric chars with underscores and lowercase
            segment.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase
          }
        }
        .mkString("_")
    }
  }
}
