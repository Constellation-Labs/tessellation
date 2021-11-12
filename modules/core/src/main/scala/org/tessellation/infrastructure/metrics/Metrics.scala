package org.tessellation.infrastructure.metrics

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.infrastructure.metrics.types.{CounterMetric, GaugeMetric}

import eu.timepit.refined.auto._

trait Metrics[F[_]] {
  def getAll: F[String]
  val successfulMigrationsCount: CounterMetric[F]
  val failedMigrationsCount: CounterMetric[F]
  val sampleGauge: GaugeMetric[F, Double]
}

object Metrics {

  def make[F[_]: Async]: F[Metrics[F]] = make[F](MetricsRegistry.make[F])

  def make[F[_]: Async](registry: MetricsRegistry[F]): F[Metrics[F]] =
    for {
      _successfulMigrationsCount <- registry.registerCounter("dag.database.migrations", "status", "successful")
      _failedMigrationsCount <- registry.registerCounter("dag.database.migrations", "status", "failed")
      _sampleGauge <- registry.registerGauge("dag.samplegauge", 2.0)
    } yield
      new Metrics[F] {
        val successfulMigrationsCount: CounterMetric[F] = _successfulMigrationsCount
        val failedMigrationsCount: CounterMetric[F] = _failedMigrationsCount
        val sampleGauge: GaugeMetric[F, Double] = _sampleGauge
        def getAll: F[String] = registry.getAll
      }
}
