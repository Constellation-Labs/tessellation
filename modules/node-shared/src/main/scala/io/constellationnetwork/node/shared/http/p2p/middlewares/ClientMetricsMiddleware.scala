package io.constellationnetwork.node.shared.http.p2p.middlewares

import java.util.concurrent.TimeUnit

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.http.p2p.middlewares.MetricsMiddleware.normalizeRoutePath
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics

import eu.timepit.refined.auto._
import org.http4s.client.Client
import org.http4s.{Request, Response}

object ClientMetricsMiddleware {

  def fromClient[F[_]: Async: Metrics](client: Client[F]): Client[F] =
    Client { (req: Request[F]) =>
      val startTime = System.nanoTime()

      client.run(req).flatMap { response =>
        Resource.liftK[F] {
          val endTime = System.nanoTime()
          val duration = FiniteDuration(endTime - startTime, TimeUnit.NANOSECONDS)

          // Extract and normalize target information
          val targetHost = req.uri.host.map(_.value).getOrElse("unknown")
          val targetPort = req.uri.port.map(_.toString).getOrElse("default")
          val routePath = normalizeRoutePath(req.uri.path.renderString)

          val tags: Seq[(Metrics.LabelName, String)] = Seq(
            Metrics.unsafeLabelName("method") -> req.method.name,
            Metrics.unsafeLabelName("status") -> response.status.code.toString,
            Metrics.unsafeLabelName("status_class") -> s"${response.status.code / 100}xx",
            Metrics.unsafeLabelName("target_host") -> targetHost,
            Metrics.unsafeLabelName("target_port") -> targetPort,
            Metrics.unsafeLabelName("route") -> routePath
          )

          // Client-specific metric keys
          val durationMetricKey: Metrics.MetricKey = "dag_http_client_request_time"
          val requestSizeMetricKey: Metrics.MetricKey = "dag_http_client_request_size"
          val responseSizeMetricKey: Metrics.MetricKey = "dag_http_client_response_size"

          val metricsRecording = for {
            _ <- Metrics[F].recordTimeHistogram(durationMetricKey, duration, tags)

            _ <- req.contentLength.traverse_ { size =>
              Metrics[F].recordSizeHistogram(requestSizeMetricKey, size, tags)
            }

            _ <- response.contentLength.traverse_ { size =>
              Metrics[F].recordSizeHistogram(responseSizeMetricKey, size, tags)
            }
          } yield ()

          Async[F].start(metricsRecording) >> response.pure[F]
        }
      }
    }

}
