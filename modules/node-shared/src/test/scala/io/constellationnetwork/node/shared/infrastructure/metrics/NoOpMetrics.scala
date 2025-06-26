package io.constellationnetwork.node.shared.infrastructure.metrics

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.{MetricKey, TagSeq}

object NoOpMetrics {
  def make: Metrics[IO] =
    new Metrics[IO] {
      override def updateGauge(key: MetricKey, value: Int): IO[Unit] = IO.unit

      override def updateGauge(key: MetricKey, value: Int, tags: TagSeq): IO[Unit] = IO.unit

      override def updateGauge(key: MetricKey, value: Long): IO[Unit] = IO.unit

      override def updateGauge(key: MetricKey, value: Long, tags: TagSeq): IO[Unit] = IO.unit

      override def updateGauge(key: MetricKey, value: Float): IO[Unit] = IO.unit

      override def updateGauge(key: MetricKey, value: Float, tags: TagSeq): IO[Unit] = IO.unit

      override def updateGauge(key: MetricKey, value: Double): IO[Unit] = IO.unit

      override def updateGauge(key: MetricKey, value: Double, tags: TagSeq): IO[Unit] = IO.unit

      override def incrementCounter(key: MetricKey, tags: TagSeq): IO[Unit] = IO.unit

      override def incrementCounterBy(key: MetricKey, value: Int): IO[Unit] = IO.unit

      override def incrementCounterBy(key: MetricKey, value: Int, tags: TagSeq): IO[Unit] = IO.unit

      override def incrementCounterBy(key: MetricKey, value: Long): IO[Unit] = IO.unit

      override def incrementCounterBy(key: MetricKey, value: Long, tags: TagSeq): IO[Unit] = IO.unit

      override def incrementCounterBy(key: MetricKey, value: Float): IO[Unit] = IO.unit

      override def incrementCounterBy(key: MetricKey, value: Float, tags: TagSeq): IO[Unit] = IO.unit

      override def incrementCounterBy(key: MetricKey, value: Double): IO[Unit] = IO.unit

      override def incrementCounterBy(key: MetricKey, value: Double, tags: TagSeq): IO[Unit] = IO.unit

      override def recordTime(key: MetricKey, duration: FiniteDuration, tags: TagSeq): IO[Unit] = IO.unit

      override def recordDistribution(key: MetricKey, value: Int): IO[Unit] = IO.unit

      override def recordDistribution(key: MetricKey, value: Int, tags: TagSeq): IO[Unit] = IO.unit

      override def recordDistribution(key: MetricKey, value: Long): IO[Unit] = IO.unit

      override def recordDistribution(key: MetricKey, value: Long, tags: TagSeq): IO[Unit] = IO.unit

      override def recordDistribution(key: MetricKey, value: Float): IO[Unit] = IO.unit

      override def recordDistribution(key: MetricKey, value: Float, tags: TagSeq): IO[Unit] = IO.unit

      override def recordDistribution(key: MetricKey, value: Double): IO[Unit] = IO.unit

      override def recordDistribution(key: MetricKey, value: Double, tags: TagSeq): IO[Unit] = IO.unit

      override private[shared] def getAllAsText: IO[String] = IO("Metrics.noopIO")

      override def timedMetric[A](operation: IO[A], metricKey: MetricKey, tags: TagSeq): IO[A] = operation

      override def genericRecordDistributionWithTimeBuckets[A: Numeric](
        key: MetricKey,
        value: A,
        timeSeconds: Float,
        tags: TagSeq
      ): IO[Unit] = IO.unit

      override def recordTimeHistogram(key: MetricKey, duration: FiniteDuration, tags: TagSeq, buckets: Array[Double]): IO[Unit] = IO.unit

      override def recordSizeHistogram(key: MetricKey, sizeBytes: Long, tags: TagSeq, buckets: Array[Double]): IO[Unit] = IO.unit
    }
}
