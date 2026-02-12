package io.constellationnetwork.node.shared.infrastructure.metrics

import cats.effect._
import cats.syntax.all._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.ClickHouseAppConfig
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.TagSeq
import io.constellationnetwork.node.shared.logger.sink.clickhouse.ClickHouseConfig
import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Factory for creating Metrics with optional ClickHouse persistence */
object MetricsFactory {

  /** Creates Metrics with Prometheus + optional ClickHouse sink.
    *
    * @param commonTags
    *   Tags added to all metrics (e.g., application name)
    * @param nodeId
    *   Node identifier for ClickHouse
    * @param environment
    *   Network environment
    * @param clickHouseConfig
    *   ClickHouse configuration (metrics disabled if metricsTableName is None)
    * @return
    *   Metrics instance
    */
  def make[F[_]: Async](
    commonTags: TagSeq,
    nodeId: PeerId,
    environment: AppEnvironment,
    clickHouseConfig: ClickHouseAppConfig
  ): Resource[F, Metrics[F]] =
    for {
      logger <- Resource.eval(Slf4jLogger.create[F])
      (gaugesR, registry) <- Metrics.make[F](commonTags)
      prometheusMetrics <- Metrics.forAsync(gaugesR, registry)
      metricsSink <- makeClickHouseSink[F](nodeId, environment, clickHouseConfig, logger)
      compositeMetrics = CompositeMetrics.make(prometheusMetrics, metricsSink)
      _ <- metricsSink.traverse_(sink => MicrometerRegistryScraper.start[F](registry, sink))
    } yield compositeMetrics

  /** Creates Prometheus-only Metrics (for backward compatibility) */
  def makePrometheusOnly[F[_]: Async](commonTags: TagSeq): Resource[F, Metrics[F]] =
    Metrics.forAsync[F](commonTags)

  private def makeClickHouseSink[F[_]: Async](
    nodeId: PeerId,
    environment: AppEnvironment,
    appConfig: ClickHouseAppConfig,
    logger: SelfAwareStructuredLogger[F]
  ): Resource[F, Option[MetricsSink[F]]] =
    ClickHouseConfig.makeMetricsConfig(appConfig) match {
      case Right(Some(metricsConfig)) =>
        ClickHouseMetricsSink
          .make[F](metricsConfig, nodeId, environment)
          .map(_.some)
          .handleErrorWith { e =>
            Resource.eval(
              logger.warn(s"ClickHouse metrics sink creation failed: ${e.getMessage}. Metrics will only go to Prometheus.")
            ) >> Resource.pure(none[MetricsSink[F]])
          }
      case Right(None) =>
        Resource.eval(
          logger.info("ClickHouse not configured. Metrics will only go to Prometheus.")
        ) >> Resource.pure(none[MetricsSink[F]])
      case Left(error) =>
        Resource.eval(
          logger.warn(s"ClickHouse config invalid: ${error.getMessage}. Metrics will only go to Prometheus.")
        ) >> Resource.pure(none[MetricsSink[F]])
    }
}
