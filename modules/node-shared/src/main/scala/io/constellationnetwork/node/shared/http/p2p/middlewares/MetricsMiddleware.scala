package io.constellationnetwork.node.shared.http.p2p.middlewares

import java.util.concurrent.TimeUnit

import cats.data.{Kleisli, OptionT}
import cats.effect.kernel.{Async, Clock, Ref}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.headers.`X-Forwarded-For`

object MetricsMiddleware {

  def isHistogramRoute(path: String): Boolean =
    // allow all routes to send metrics
    true

  def bucketName: Metrics.LabelName = Metrics.unsafeLabelName("time_bucket")

  def bucketLabel(duration: FiniteDuration): String =
    if (duration.toMillis > 10_000) {
      "gt_10s"
    } else if (duration.toMillis > 1000) {
      "gt_1s"
    } else {
      "lt_1s"
    }

  def histogramTags(routePath: String): Seq[(Metrics.LabelName, String)] = Seq(
    Metrics.unsafeLabelName("route") -> routePath
  )

  def apply[F[_]: Async: Metrics](): HttpRoutes[F] => HttpRoutes[F] = { routes =>
    Kleisli { req =>
      OptionT.liftF(Clock[F].monotonic).flatMap { startTime =>
        routes(req).semiflatMap { response =>
          for {
            endTime <- Clock[F].monotonic
            duration = endTime - startTime
            // Extract route path and normalize it for metric naming
            routePath = normalizeRoutePath(req.pathInfo.renderString)
            actualIp = req.remote.map(_.host.toString).getOrElse("unknown")
            forwardedIp = req.headers
              .get[`X-Forwarded-For`]
              .map(_.values.head.toString.split(",").head.trim)
              .getOrElse("none")

            // Cannot compile time infer type for Seq
            allTags: Seq[(Metrics.LabelName, String)] =
              Seq(
                Metrics.unsafeLabelName("method") -> req.method.name,
                Metrics.unsafeLabelName("status") -> response.status.code.toString,
                Metrics.unsafeLabelName("route") -> routePath,
                Metrics.unsafeLabelName("status_class") -> s"${response.status.code / 100}xx",
                Metrics.unsafeLabelName("actual_ip") -> actualIp,
                Metrics.unsafeLabelName("forwarded_ip") -> forwardedIp,
                bucketName -> bucketLabel(duration)
              )

            histogramTagsSeq: Seq[(Metrics.LabelName, String)] = histogramTags(routePath)

            // Generic HTTP metrics with route as label
            durationMetricKey: Metrics.MetricKey = "dag_http_request_time"
            requestSizeMetricKey: Metrics.MetricKey = "dag_http_request_size"
            responseSizeMetricKey: Metrics.MetricKey = "dag_http_response_size"
            requestCounterMetricKey: Metrics.MetricKey = "dag_http_request_count"

            // Record metrics asynchronously without blocking the response
            metricsRecording = for {
              _ <- Metrics[F].incrementCounter(requestCounterMetricKey, allTags)
              _ <-
                if (isHistogramRoute(req.pathInfo.renderString)) {
                  Metrics[F].recordTimeHistogram(durationMetricKey, duration, histogramTagsSeq) >>
                    req.contentLength.traverse_ { size =>
                      Metrics[F].recordSizeHistogram(requestSizeMetricKey, size, histogramTagsSeq)
                    }
                } else {
                  Async[F].unit
                }
              // 4. Request size histograms (both route-specific and generic)

            } yield ()
            _ <- Async[F].start(metricsRecording)
            finalResponse <-
              if (isHistogramRoute(req.pathInfo.renderString))
                Ref[F].of(0L).map { bytesRef =>
                  response.withBodyStream(
                    response.body.chunks
                      .evalTap(chunk => bytesRef.update(_ + chunk.size.toLong))
                      .unchunks
                      .onFinalize(
                        bytesRef.get.flatMap { total =>
                          Metrics[F].recordSizeHistogram(responseSizeMetricKey, total, histogramTagsSeq)
                        }
                          .handleErrorWith(_ => Async[F].unit)
                      )
                  )
                }
              else response.pure[F]
          } yield finalResponse
        }
      }
    }
  }

  /** Normalize route path for use in metric names Examples:
    *   - "/api/v1/users/123" -> "api_v1_users_id"
    *   - "/health" -> "health"
    *   - "/metrics" -> "metrics"
    *   - "/" -> "root"
    */
  def normalizeRoutePath(path: String): String = {
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
          } else if (segment.matches("(?i).*dag[1-9A-HJ-NP-Za-km-z].*")) {
            // DAG address pattern (base58, case insensitive)
            "address"
          } else if (segment.toLowerCase.startsWith("state_channel_dag")) {
            "state_channel_dag"
          } else {
            // Replace non-alphanumeric chars with underscores and lowercase
            segment.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase
          }
        }
        .mkString("_")
    }
  }
}
