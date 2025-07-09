package io.constellationnetwork.node.shared.http.p2p.middlewares

import java.util.concurrent.TimeUnit

import cats.effect.kernel.{Async, Clock, Resource}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.http.p2p.middlewares.MetricsMiddleware._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics

import eu.timepit.refined.auto._
import org.http4s.client.Client
import org.http4s.{Request, Response}

object ClientMetricsMiddleware {

  def fromClient[F[_]: Async: Metrics](client: Client[F]): Client[F] =
    Client { (req: Request[F]) =>
      Resource.liftK[F](Clock[F].monotonic).flatMap { startTime =>
        client.run(req).flatMap { response =>
          Resource.liftK[F] {
            for {
              endTime <- Clock[F].monotonic
              duration = endTime - startTime

              // Extract and normalize target information
              targetHost = req.uri.host.map(_.value).getOrElse("unknown")
              targetPort = req.uri.port.map(_.toString).getOrElse("default")
              routePath = normalizeRoutePath(req.uri.path.renderString)

              // All tags for counter metrics (similar to server-side)
              allTags: Seq[(Metrics.LabelName, String)] = Seq(
                Metrics.unsafeLabelName("method") -> req.method.name,
                Metrics.unsafeLabelName("status") -> response.status.code.toString,
                Metrics.unsafeLabelName("status_class") -> s"${response.status.code / 100}xx",
                Metrics.unsafeLabelName("target_host") -> targetHost,
                Metrics.unsafeLabelName("target_port") -> targetPort,
                Metrics.unsafeLabelName("route") -> routePath,
                bucketName -> bucketLabel(duration)
              )

              // Histogram tags (limited labels)
              histogramTagsSeq: Seq[(Metrics.LabelName, String)] = histogramTags(routePath)

              // Client-specific metric keys
              durationMetricKey: Metrics.MetricKey = "dag_http_client_request_time"
              requestSizeMetricKey: Metrics.MetricKey = "dag_http_client_request_size"
              responseSizeMetricKey: Metrics.MetricKey = "dag_http_client_response_size"
              requestCounterMetricKey: Metrics.MetricKey = "dag_http_client_request_count"

              metricsRecording = for {
                _ <- Metrics[F].incrementCounter(requestCounterMetricKey, allTags)
                _ <-
                  if (isHistogramRoute(req.uri.path.renderString)) {
                    Metrics[F].recordTimeHistogram(durationMetricKey, duration, histogramTagsSeq) >>
                      req.contentLength.traverse_ { size =>
                        Metrics[F].recordSizeHistogram(requestSizeMetricKey, size, histogramTagsSeq)
                      } >>
                      response.contentLength.traverse_ { size =>
                        Metrics[F].recordSizeHistogram(responseSizeMetricKey, size, histogramTagsSeq)
                      }
                  } else {
                    Async[F].unit
                  }
              } yield ()

              _ <- Async[F].start(metricsRecording)
            } yield response
          }
        }
      }
    }

}
