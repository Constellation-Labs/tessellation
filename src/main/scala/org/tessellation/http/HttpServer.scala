package org.tessellation.http

import cats.effect._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.prometheus.client.exporter.common.TextFormat
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.tessellation.metrics.Metrics

import java.io.{StringWriter, Writer}
import scala.concurrent.ExecutionContext.Implicits.global

class HttpServer(
  publicRoutes: HttpRoutes[IO],
  peerRoutes: HttpRoutes[IO],
  metrics: Metrics
) {
  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO] = IO.timer(global)

  private val metricsService: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "micrometer-metrics" =>
      IO.delay {
        val writer: Writer = new StringWriter()
        TextFormat.write004(writer, metrics.collectorRegistry.metricFamilySamples())
        writer.toString
      }.flatMap(Ok(_))
  }

  private val publicAPI = BlazeServerBuilder[IO](global)
    .bindHttp(9001, "0.0.0.0")
    .withHttpApp(Router("/" -> publicRoutes).orNotFound)

  private val metricsAPI = BlazeServerBuilder[IO](global)
    .bindHttp(9000, "0.0.0.0")
    .withHttpApp(Router("/" -> metricsService).orNotFound)

  def run() = publicAPI.serve.merge(metricsAPI.serve)
}

object HttpServer {

  def apply(publicRoutes: HttpRoutes[IO], peerRoutes: HttpRoutes[IO], metrics: Metrics): HttpServer =
    new HttpServer(publicRoutes, peerRoutes, metrics)
}
