package org.tesselation.infrastructure.metrics

object types {
  sealed trait Metric[F[_]]

  trait CounterMetric[F[_]] extends Metric[F] {
    def count: F[Double]
    def increment: F[Unit]
    def increment(by: Double): F[Unit]
  }

  trait GaugeMetric[F[_], A] extends Metric[F] {
    def set(value: A): F[Unit]
  }
}
