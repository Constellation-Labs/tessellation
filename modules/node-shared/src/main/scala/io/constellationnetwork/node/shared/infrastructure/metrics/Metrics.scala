package io.constellationnetwork.node.shared.infrastructure.metrics

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.{List => JList}

import cats.Applicative
import cats.effect.{Async, Resource}
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._

import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics._

import better.files.File
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.string.MatchesRegex
import io.chrisdavenport.mapref.MapRef
import io.micrometer.core.instrument.binder.jvm._
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.{
  DiskSpaceMetrics => SystemDiskSpaceMetrics,
  FileDescriptorMetrics,
  ProcessorMetrics,
  UptimeMetrics
}
import io.micrometer.core.instrument.{DistributionSummary, Tag}
import io.micrometer.prometheus.{PrometheusConfig, PrometheusMeterRegistry}
import io.prometheus.client.exporter.common.TextFormat

trait Metrics[F[_]] {

  def updateGauge(key: MetricKey, value: Int): F[Unit]
  def updateGauge(key: MetricKey, value: Int, tags: TagSeq): F[Unit]
  def updateGauge(key: MetricKey, value: Long): F[Unit]
  def updateGauge(key: MetricKey, value: Long, tags: TagSeq): F[Unit]
  def updateGauge(key: MetricKey, value: Float): F[Unit]
  def updateGauge(key: MetricKey, value: Float, tags: TagSeq): F[Unit]
  def updateGauge(key: MetricKey, value: Double): F[Unit]
  def updateGauge(key: MetricKey, value: Double, tags: TagSeq): F[Unit]

  def incrementCounter(key: MetricKey, tags: TagSeq = Seq.empty): F[Unit]
  def incrementCounterBy(key: MetricKey, value: Int): F[Unit]
  def incrementCounterBy(key: MetricKey, value: Int, tags: TagSeq): F[Unit]
  def incrementCounterBy(key: MetricKey, value: Long): F[Unit]
  def incrementCounterBy(key: MetricKey, value: Long, tags: TagSeq): F[Unit]
  def incrementCounterBy(key: MetricKey, value: Float): F[Unit]
  def incrementCounterBy(key: MetricKey, value: Float, tags: TagSeq): F[Unit]
  def incrementCounterBy(key: MetricKey, value: Double): F[Unit]
  def incrementCounterBy(key: MetricKey, value: Double, tags: TagSeq): F[Unit]

  def recordTime(key: MetricKey, duration: FiniteDuration, tags: TagSeq = Seq.empty): F[Unit]

  def recordDistribution(key: MetricKey, value: Int): F[Unit]
  def recordDistribution(key: MetricKey, value: Int, tags: TagSeq): F[Unit]
  def recordDistribution(key: MetricKey, value: Long): F[Unit]
  def recordDistribution(key: MetricKey, value: Long, tags: TagSeq): F[Unit]
  def recordDistribution(key: MetricKey, value: Float): F[Unit]
  def recordDistribution(key: MetricKey, value: Float, tags: TagSeq): F[Unit]
  def recordDistribution(key: MetricKey, value: Double): F[Unit]
  def recordDistribution(key: MetricKey, value: Double, tags: TagSeq): F[Unit]

  private[shared] def getAllAsText: F[String]

  def timedMetric[A](operation: F[A], metricKey: MetricKey, tags: TagSeq = Seq.empty): F[A]

  def genericRecordDistributionWithTimeBuckets[A: Numeric](
    key: MetricKey,
    value: A,
    timeSeconds: Float,
    tags: TagSeq = Seq()
  ): F[Unit]

  def recordTimeHistogram(
    key: MetricKey,
    duration: FiniteDuration,
    tags: TagSeq = Seq.empty,
    buckets: Array[Double] = timeSecondsBuckets
  ): F[Unit]

  def recordSizeHistogram(
    key: MetricKey,
    sizeBytes: Long,
    tags: TagSeq = Seq.empty,
    buckets: Array[Double] = sizeBytesBuckets
  ): F[Unit]
}

object Metrics {

  import org.openjdk.jol.info.GraphLayout

  val sizeBytesBuckets: Array[Double] = Array(
    1e3, 5e3, 10e3, 100e3, 500e3, // 100KB to 500KB
    1e6, 5e6, 10e6, // 1MB to 10MB
    25e6, 50e6, 100e6 // 25MB to 100MB
  )

  val timeSecondsBuckets: Array[Double] = Array(
    0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 45.0, 90.0
  )

  def sizeInKB(obj: Any): Double =
    GraphLayout.parseInstance(obj).totalSize() / 1024.0

  type MetricKey = String Refined MatchesRegex["dag_[a-z0-9]+(?:_[a-z0-9]+)*"]
  type LabelName = String Refined MatchesRegex["[a-z0-9]+(?:_[a-z0-9]+)*"]
  type TagSeq = Seq[(LabelName, String)]
  type AtomicDouble = AtomicReference[Double]
  def unsafeLabelName(s: String): LabelName = Refined.unsafeApply(s)

  private def toMicrometerTags(tags: TagSeq): JList[Tag] =
    tags.map { case (k, v) => Tag.of(k, v) }.asJava

  def apply[F[_]: Metrics]: Metrics[F] = implicitly

  def make[F[_]: Async](
    commonTags: TagSeq
  ): Resource[F, (MapRef[F, (MetricKey, TagSeq), Option[AtomicDouble]], PrometheusMeterRegistry)] =
    Resource.make {
      MapRef
        .ofSingleImmutableMap[F, (MetricKey, TagSeq), AtomicDouble](Map.empty)
        .product(Async[F].delay {
          val registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
          registry.config().commonTags(toMicrometerTags(commonTags))
          List(
            new ClassLoaderMetrics(),
            new JvmGcMetrics(),
            new JvmHeapPressureMetrics(),
            new JvmInfoMetrics(),
            new JvmMemoryMetrics(),
            new JvmThreadMetrics(),
            new SystemDiskSpaceMetrics(File(System.getProperty("user.dir")).toJava),
            new FileDescriptorMetrics(),
            new ProcessorMetrics(),
            new UptimeMetrics(),
            new LogbackMetrics()
          ).foreach(_.bindTo(registry))
          registry
        })
    }(_ => Applicative[F].unit)

  def forAsync[F[_]: Async](commonTags: TagSeq): Resource[F, Metrics[F]] = make(commonTags).map {
    case (gaugesR, registry) =>
      new Metrics[F] {

        def recordTimeHistogram(
          key: MetricKey,
          duration: FiniteDuration,
          tags: TagSeq = Seq.empty,
          buckets: Array[Double] = timeSecondsBuckets
        ): F[Unit] = {
          val durationSeconds = duration.toUnit(TimeUnit.SECONDS)
          Async[F].delay {
            DistributionSummary
              .builder(s"${key}_duration_seconds")
              .baseUnit("seconds")
              .serviceLevelObjectives(buckets: _*)
              .tags(toMicrometerTags(tags))
              .register(registry)
              .record(durationSeconds)
          }
        }
        def recordSizeHistogram(
          key: MetricKey,
          sizeBytes: Long,
          tags: TagSeq = Seq.empty,
          buckets: Array[Double] = sizeBytesBuckets
        ): F[Unit] =
          Async[F].delay {
            DistributionSummary
              .builder(s"${key}_bytes")
              .baseUnit("bytes")
              .serviceLevelObjectives(buckets: _*)
              .tags(toMicrometerTags(tags))
              .register(registry)
              .record(sizeBytes.toDouble)
          }
        def updateGauge(key: MetricKey, value: Int): F[Unit] =
          genericUpdateGauge(key, value, Seq.empty)

        def updateGauge(key: MetricKey, value: Int, tags: TagSeq): F[Unit] =
          genericUpdateGauge(key, value, tags)

        def updateGauge(key: MetricKey, value: Long): F[Unit] =
          genericUpdateGauge(key, value, Seq.empty)

        def updateGauge(key: MetricKey, value: Long, tags: TagSeq): F[Unit] =
          genericUpdateGauge(key, value, tags)

        def updateGauge(key: MetricKey, value: Float): F[Unit] =
          genericUpdateGauge(key, value, Seq.empty)

        def updateGauge(key: MetricKey, value: Float, tags: TagSeq): F[Unit] =
          genericUpdateGauge(key, value, tags)

        def updateGauge(key: MetricKey, value: Double): F[Unit] =
          genericUpdateGauge(key, value, Seq.empty)

        def updateGauge(key: MetricKey, value: Double, tags: TagSeq): F[Unit] =
          genericUpdateGauge(key, value, tags)

        private def genericUpdateGauge[A: Numeric](key: MetricKey, value: A, tags: TagSeq): F[Unit] = {
          val doubleValue = Numeric[A].toDouble(value)
          gaugesR((key, tags)).get.flatMap {
            case Some(atomicDouble) => Async[F].delay(atomicDouble.set(doubleValue))
            case None =>
              gaugesR((key, tags)).modify {
                case s @ Some(atomicDouble) => (s, (atomicDouble, false))
                case None =>
                  val atomicRef = new AtomicDouble(doubleValue)
                  (atomicRef.some, (atomicRef, true))
              }.flatMap {
                case (atomicDouble, created) =>
                  created
                    .pure[F]
                    .ifM(
                      Async[F]
                        .delay(
                          registry.gauge[AtomicDouble](
                            key,
                            toMicrometerTags(tags),
                            atomicDouble,
                            (r: AtomicDouble) => r.get()
                          )
                        )
                        .void,
                      Async[F].delay(atomicDouble.set(doubleValue))
                    )
              }
          }
        }

        def incrementCounter(key: MetricKey, tags: TagSeq): F[Unit] =
          genericIncrementCounterBy(key, 1.0, tags)

        def incrementCounterBy(key: MetricKey, value: Int): F[Unit] =
          genericIncrementCounterBy(key, value, Seq.empty)

        def incrementCounterBy(key: MetricKey, value: Int, tags: TagSeq): F[Unit] =
          genericIncrementCounterBy(key, value, tags)

        def incrementCounterBy(key: MetricKey, value: Long): F[Unit] =
          genericIncrementCounterBy(key, value, Seq.empty)

        def incrementCounterBy(key: MetricKey, value: Long, tags: TagSeq): F[Unit] =
          genericIncrementCounterBy(key, value, tags)

        def incrementCounterBy(key: MetricKey, value: Float): F[Unit] =
          genericIncrementCounterBy(key, value, Seq.empty)

        def incrementCounterBy(key: MetricKey, value: Float, tags: TagSeq): F[Unit] =
          genericIncrementCounterBy(key, value, tags)

        def incrementCounterBy(key: MetricKey, value: Double): F[Unit] =
          genericIncrementCounterBy(key, value, Seq.empty)

        def incrementCounterBy(key: MetricKey, value: Double, tags: TagSeq): F[Unit] =
          genericIncrementCounterBy(key, value, tags)

        private def genericIncrementCounterBy[A: Numeric](key: MetricKey, value: A, tags: TagSeq): F[Unit] =
          Async[F].delay {
            registry.counter(key, toMicrometerTags(tags)).increment(Numeric[A].toDouble(value))
          }

//        @deprecated("This does not properly record buckets due to a micrometer / prometheus mismatch")
        def recordTime(key: MetricKey, duration: FiniteDuration, tags: TagSeq): F[Unit] =
          Async[F].delay {
            registry.timer(key, toMicrometerTags(tags)).record(duration.toJava)
          }

//        @deprecated("This does not properly record buckets due to a micrometer / prometheus mismatch")
        def timedMetric[A](operation: F[A], metricKey: MetricKey, tags: TagSeq): F[A] =
          Async[F].realTime.flatMap { start =>
            operation.flatTap { _ =>
              Async[F].realTime.flatMap { end =>
                val duration = FiniteDuration(end.toNanos - start.toNanos, TimeUnit.NANOSECONDS)
                recordTime(metricKey, duration, tags)
              }
            }
          }

        def recordDistribution(key: MetricKey, value: Int): F[Unit] =
          genericRecordDistribution(key, value, Seq.empty)

        def recordDistribution(key: MetricKey, value: Int, tags: TagSeq): F[Unit] =
          genericRecordDistribution(key, value, tags)

        def recordDistribution(key: MetricKey, value: Long): F[Unit] =
          genericRecordDistribution(key, value, Seq.empty)

        def recordDistribution(key: MetricKey, value: Long, tags: TagSeq): F[Unit] =
          genericRecordDistribution(key, value, tags)

        def recordDistribution(key: MetricKey, value: Float): F[Unit] =
          genericRecordDistribution(key, value, Seq.empty)

        def recordDistribution(key: MetricKey, value: Float, tags: TagSeq): F[Unit] =
          genericRecordDistribution(key, value, tags)

        def recordDistribution(key: MetricKey, value: Double): F[Unit] =
          genericRecordDistribution(key, value, Seq.empty)

        def recordDistribution(key: MetricKey, value: Double, tags: TagSeq): F[Unit] =
          genericRecordDistribution(key, value, tags)

//        @deprecated("This does not properly record buckets due to a micrometer / prometheus mismatch")
        private def genericRecordDistribution[A: Numeric](key: MetricKey, value: A, tags: TagSeq): F[Unit] =
          Async[F].delay {
            registry.summary(key, toMicrometerTags(tags)).record(Numeric[A].toDouble(value))
          }

        // Alternative to above timer to add labels directly -- uses same format as above but
        // Adds labels as a workaround to missing information
        // This function is still useful in the event where you need to record a distribution with lots of labels,
        // to avoid an outer product of buckets times labels.
        // It should be refactored away from the prior timer declarations however.
        def genericRecordDistributionWithTimeBuckets[A: Numeric](
          key: MetricKey,
          value: A,
          timeSeconds: Float,
          tags: TagSeq = Seq()
        ): F[Unit] = {
          val timeBucket = if (timeSeconds > 120f) {
            "120s+"
          } else {
            val bucketStart = (timeSeconds / 5f).toInt * 5
            val bucketEnd = bucketStart + 5
            s"${bucketStart}-${bucketEnd}s"
          }
          val tagsWithBucket = tags :+ (unsafeLabelName("time_bucket") -> timeBucket)
          genericRecordDistribution(key, value, tagsWithBucket)
        }
        def getAllAsText: F[String] = Async[F].delay {
          registry.scrape(TextFormat.CONTENT_TYPE_OPENMETRICS_100)
        }
      }
  }

}
