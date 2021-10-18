package org.tesselation.infrastructure.metrics
import cats.effect.Async

import org.tesselation.infrastructure.metrics.MetricsRegistry.MetricKey
import org.tesselation.infrastructure.metrics.types._

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.string.StartsWith
import io.micrometer.core.instrument.Tags
import io.micrometer.prometheus.{PrometheusConfig, PrometheusMeterRegistry}
import io.prometheus.client.exporter.common.TextFormat

trait MetricsRegistry[F[_]] {
  def registerCounter(key: MetricKey, tags: String*): F[CounterMetric[F]]

  def registerGauge[A: Numeric](
    key: MetricKey,
    value: A,
    tags: String*
  )(implicit C: AtomicConversion[F, A]): F[GaugeMetric[F, A]]

  def getAll: F[String]
}

object MetricsRegistry {
  type MetricKey = String Refined StartsWith["dag."]

  def make[F[_]: Async]: MetricsRegistry[F] =
    make(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT))

  def make[F[_]: Async](prometheusRegistry: PrometheusMeterRegistry): MetricsRegistry[F] = new MetricsRegistry[F] {

    def registerCounter(key: MetricKey, tags: String*): F[CounterMetric[F]] = Async[F].delay {
      val counter = prometheusRegistry.counter(key, tags: _*)
      new CounterMetric[F] {
        override def count: F[Double] = Async[F].delay(counter.count())
        override def increment: F[Unit] = Async[F].delay(counter.increment())
        override def increment(by: Double): F[Unit] = Async[F].delay(counter.increment(by))
      }
    }

    override def registerGauge[A: Numeric](
      key: MetricKey,
      value: A,
      tags: String*
    )(implicit C: AtomicConversion[F, A]): F[GaugeMetric[F, A]] = Async[F].delay {
      val gauge = prometheusRegistry.gauge(key, Tags.of(tags: _*), C.convert(value))
      new GaugeMetric[F, A] {
        override def set(value: A): F[Unit] = gauge.setAsync(value)
      }
    }

    override def getAll: F[String] = Async[F].delay {
      prometheusRegistry.scrape(TextFormat.CONTENT_TYPE_OPENMETRICS_100)
    }
  }
}
