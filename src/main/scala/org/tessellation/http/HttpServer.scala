package org.tessellation.http

import java.io.{StringWriter, Writer}

import cats.effect._
import cats.syntax.all._
import io.prometheus.client.exporter.common.TextFormat
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.tessellation.metrics.Metrics

import scala.concurrent.ExecutionContext.Implicits.global

class HttpServer[F[_]: Concurrent: ConcurrentEffect: Timer: ContextShift](
  publicRoutes: HttpRoutes[F],
  peerRoutes: HttpRoutes[F],
  metrics: Metrics
) extends Http4sDsl[F] {

  private val metricsService: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "micrometer-metrics" =>
      Sync[F].delay {
        val writer: Writer = new StringWriter()
        TextFormat.write004(writer, metrics.collectorRegistry.metricFamilySamples())
        writer.toString
      }.flatMap(a => Ok(a))
  }

  private val publicAPI = BlazeServerBuilder[F](global)
    .bindHttp(9000)
    .withHttpApp(Router("/" -> publicRoutes.combineK(metricsService)).orNotFound)

  private val peerAPI = BlazeServerBuilder[F](global)
    .bindHttp(9001)
    .withHttpApp(Router("/" -> peerRoutes).orNotFound)

  def run(): fs2.Stream[F, ExitCode] = publicAPI.serve.merge(peerAPI.serve)
}

object HttpServer {

  def apply[F[_]: Concurrent: ConcurrentEffect: Timer: ContextShift](
    publicRoutes: HttpRoutes[F],
    peerRoutes: HttpRoutes[F],
    metrics: Metrics
  ): HttpServer[F] =
    new HttpServer(publicRoutes, peerRoutes, metrics)
}
