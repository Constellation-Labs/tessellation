package org.tessellation.sdk.infrastructure.metrics

import java.util.concurrent.atomic.AtomicReference
import java.util.{List => JList}

import cats.Applicative
import cats.effect.{Async, Resource}
import cats.syntax.all._

import scala.jdk.CollectionConverters._

import org.tessellation.sdk.infrastructure.metrics.Metrics.{MetricKey, TagSeq}

import better.files.File
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.string.MatchesRegex
import io.chrisdavenport.mapref.MapRef
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.binder.jvm._
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.{
  DiskSpaceMetrics => SystemDiskSpaceMetrics,
  FileDescriptorMetrics,
  ProcessorMetrics,
  UptimeMetrics
}
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

  private[sdk] def getAllAsText: F[String]
}

object Metrics {

  type MetricKey = String Refined MatchesRegex["dag_[a-z0-9]+(?:_[a-z0-9]+)*"]
  type LabelName = String Refined MatchesRegex["[a-z0-9]+(?:_[a-z0-9]+)*"]
  type TagSeq = Seq[(LabelName, String)]
  type AtomicDouble = AtomicReference[Double]

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

        def getAllAsText: F[String] = Async[F].delay {
          registry.scrape(TextFormat.CONTENT_TYPE_OPENMETRICS_100)
        }
      }
  }

}
