package io.constellationnetwork.node.shared.infrastructure.metrics

import java.util.concurrent.TimeUnit

import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics._

/** Wraps the existing Prometheus Metrics and adds ClickHouse sink for persistence */
object CompositeMetrics {

  def make[F[_]: Async](
    prometheus: Metrics[F],
    clickHouseSink: Option[MetricsSink[F]]
  ): Metrics[F] = new Metrics[F] {

    private def writeToClickHouse(name: MetricKey, value: Double, metricType: MetricType, tags: TagSeq): F[Unit] =
      clickHouseSink.traverse_(_.write(MetricEntry(name.value, metricType, value, tags)))

    // Gauges
    def updateGauge(key: MetricKey, value: Int): F[Unit] =
      prometheus.updateGauge(key, value) >> writeToClickHouse(key, value.toDouble, MetricType.Gauge, Seq.empty)

    def updateGauge(key: MetricKey, value: Int, tags: TagSeq): F[Unit] =
      prometheus.updateGauge(key, value, tags) >> writeToClickHouse(key, value.toDouble, MetricType.Gauge, tags)

    def updateGauge(key: MetricKey, value: Long): F[Unit] =
      prometheus.updateGauge(key, value) >> writeToClickHouse(key, value.toDouble, MetricType.Gauge, Seq.empty)

    def updateGauge(key: MetricKey, value: Long, tags: TagSeq): F[Unit] =
      prometheus.updateGauge(key, value, tags) >> writeToClickHouse(key, value.toDouble, MetricType.Gauge, tags)

    def updateGauge(key: MetricKey, value: Float): F[Unit] =
      prometheus.updateGauge(key, value) >> writeToClickHouse(key, value.toDouble, MetricType.Gauge, Seq.empty)

    def updateGauge(key: MetricKey, value: Float, tags: TagSeq): F[Unit] =
      prometheus.updateGauge(key, value, tags) >> writeToClickHouse(key, value.toDouble, MetricType.Gauge, tags)

    def updateGauge(key: MetricKey, value: Double): F[Unit] =
      prometheus.updateGauge(key, value) >> writeToClickHouse(key, value, MetricType.Gauge, Seq.empty)

    def updateGauge(key: MetricKey, value: Double, tags: TagSeq): F[Unit] =
      prometheus.updateGauge(key, value, tags) >> writeToClickHouse(key, value, MetricType.Gauge, tags)

    // Counters
    def incrementCounter(key: MetricKey, tags: TagSeq): F[Unit] =
      prometheus.incrementCounter(key, tags) >> writeToClickHouse(key, 1.0, MetricType.Counter, tags)

    def incrementCounterBy(key: MetricKey, value: Int): F[Unit] =
      prometheus.incrementCounterBy(key, value) >> writeToClickHouse(key, value.toDouble, MetricType.Counter, Seq.empty)

    def incrementCounterBy(key: MetricKey, value: Int, tags: TagSeq): F[Unit] =
      prometheus.incrementCounterBy(key, value, tags) >> writeToClickHouse(key, value.toDouble, MetricType.Counter, tags)

    def incrementCounterBy(key: MetricKey, value: Long): F[Unit] =
      prometheus.incrementCounterBy(key, value) >> writeToClickHouse(key, value.toDouble, MetricType.Counter, Seq.empty)

    def incrementCounterBy(key: MetricKey, value: Long, tags: TagSeq): F[Unit] =
      prometheus.incrementCounterBy(key, value, tags) >> writeToClickHouse(key, value.toDouble, MetricType.Counter, tags)

    def incrementCounterBy(key: MetricKey, value: Float): F[Unit] =
      prometheus.incrementCounterBy(key, value) >> writeToClickHouse(key, value.toDouble, MetricType.Counter, Seq.empty)

    def incrementCounterBy(key: MetricKey, value: Float, tags: TagSeq): F[Unit] =
      prometheus.incrementCounterBy(key, value, tags) >> writeToClickHouse(key, value.toDouble, MetricType.Counter, tags)

    def incrementCounterBy(key: MetricKey, value: Double): F[Unit] =
      prometheus.incrementCounterBy(key, value) >> writeToClickHouse(key, value, MetricType.Counter, Seq.empty)

    def incrementCounterBy(key: MetricKey, value: Double, tags: TagSeq): F[Unit] =
      prometheus.incrementCounterBy(key, value, tags) >> writeToClickHouse(key, value, MetricType.Counter, tags)

    // Time
    def recordTime(key: MetricKey, duration: FiniteDuration, tags: TagSeq): F[Unit] =
      prometheus.recordTime(key, duration, tags) >> writeToClickHouse(key, duration.toMillis.toDouble, MetricType.Histogram, tags)

    def timedMetric[A](operation: F[A], key: MetricKey, tags: TagSeq): F[A] =
      Async[F].realTime.flatMap { start =>
        operation.flatTap { _ =>
          Async[F].realTime.flatMap { end =>
            val duration = FiniteDuration(end.toNanos - start.toNanos, TimeUnit.NANOSECONDS)
            recordTime(key, duration, tags)
          }
        }
      }

    def recordTimeHistogram(key: MetricKey, duration: FiniteDuration, tags: TagSeq, buckets: Array[Double]): F[Unit] =
      prometheus.recordTimeHistogram(key, duration, tags, buckets) >>
        writeToClickHouse(key, duration.toMillis.toDouble, MetricType.Histogram, tags)

    def recordSizeHistogram(key: MetricKey, sizeBytes: Long, tags: TagSeq, buckets: Array[Double]): F[Unit] =
      prometheus.recordSizeHistogram(key, sizeBytes, tags, buckets) >>
        writeToClickHouse(key, sizeBytes.toDouble, MetricType.Histogram, tags)

    // Distribution
    def recordDistribution(key: MetricKey, value: Int): F[Unit] =
      prometheus.recordDistribution(key, value) >> writeToClickHouse(key, value.toDouble, MetricType.Distribution, Seq.empty)

    def recordDistribution(key: MetricKey, value: Int, tags: TagSeq): F[Unit] =
      prometheus.recordDistribution(key, value, tags) >> writeToClickHouse(key, value.toDouble, MetricType.Distribution, tags)

    def recordDistribution(key: MetricKey, value: Long): F[Unit] =
      prometheus.recordDistribution(key, value) >> writeToClickHouse(key, value.toDouble, MetricType.Distribution, Seq.empty)

    def recordDistribution(key: MetricKey, value: Long, tags: TagSeq): F[Unit] =
      prometheus.recordDistribution(key, value, tags) >> writeToClickHouse(key, value.toDouble, MetricType.Distribution, tags)

    def recordDistribution(key: MetricKey, value: Float): F[Unit] =
      prometheus.recordDistribution(key, value) >> writeToClickHouse(key, value.toDouble, MetricType.Distribution, Seq.empty)

    def recordDistribution(key: MetricKey, value: Float, tags: TagSeq): F[Unit] =
      prometheus.recordDistribution(key, value, tags) >> writeToClickHouse(key, value.toDouble, MetricType.Distribution, tags)

    def recordDistribution(key: MetricKey, value: Double): F[Unit] =
      prometheus.recordDistribution(key, value) >> writeToClickHouse(key, value.toDouble, MetricType.Distribution, Seq.empty)

    def recordDistribution(key: MetricKey, value: Double, tags: TagSeq): F[Unit] =
      prometheus.recordDistribution(key, value, tags) >> writeToClickHouse(key, value, MetricType.Distribution, tags)

    def genericRecordDistributionWithTimeBuckets[A: Numeric](key: MetricKey, value: A, timeSeconds: Float, tags: TagSeq): F[Unit] =
      prometheus.genericRecordDistributionWithTimeBuckets(key, value, timeSeconds, tags) >>
        writeToClickHouse(key, Numeric[A].toDouble(value), MetricType.Distribution, tags)

    private[shared] def getAllAsText: F[String] = prometheus.getAllAsText
  }
}
