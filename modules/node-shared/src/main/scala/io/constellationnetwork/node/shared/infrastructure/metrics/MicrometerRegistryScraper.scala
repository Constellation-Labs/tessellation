package io.constellationnetwork.node.shared.infrastructure.metrics

import cats.effect._
import cats.syntax.all._

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.safeLabelName

import io.micrometer.core.instrument.{Meter, Statistic}
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

object MicrometerRegistryScraper {

  def start[F[_]: Async](
    registry: PrometheusMeterRegistry,
    sink: MetricsSink[F]
  ): Resource[F, Unit] = {
    val scrape: F[Unit] = Async[F].delay(registry.getMeters.asScala.toList).flatMap { meters =>
      meters.traverse_ { meter =>
        val id = meter.getId
        val baseName = id.getName.replace('.', '_')

        if (baseName.startsWith("dag_"))
          Async[F].unit
        else {
          val tags = id.getTags.asScala.toList.flatMap { tag =>
            safeLabelName(tag.getKey.replace('.', '_')).map(name => (name, tag.getValue))
          }
          val metricType = meterTypeToMetricType(id.getType)

          Async[F].delay(meter.measure().asScala.toList).flatMap { measurements =>
            if (measurements.size == 1) {
              val value = measurements.head.getValue
              writeIfValid(sink, baseName, metricType, value, tags)
            } else {
              measurements.traverse_ { m =>
                val suffix = statisticSuffix(m.getStatistic)
                val name = s"${baseName}$suffix"
                writeIfValid(sink, name, metricType, m.getValue, tags)
              }
            }
          }
        }
      }
    }

    Spawn[F].background((Temporal[F].sleep(15.seconds) >> scrape).foreverM).void
  }

  private def writeIfValid[F[_]: Async](
    sink: MetricsSink[F],
    name: String,
    metricType: MetricType,
    value: Double,
    tags: List[(Metrics.LabelName, String)]
  ): F[Unit] =
    if (value.isNaN || value.isInfinite) Async[F].unit
    else sink.write(MetricEntry(name, metricType, value, tags))

  private def meterTypeToMetricType(meterType: Meter.Type): MetricType = meterType match {
    case Meter.Type.GAUGE                => MetricType.Gauge
    case Meter.Type.COUNTER              => MetricType.Counter
    case Meter.Type.TIMER                => MetricType.Histogram
    case Meter.Type.DISTRIBUTION_SUMMARY => MetricType.Distribution
    case Meter.Type.LONG_TASK_TIMER      => MetricType.Histogram
    case Meter.Type.OTHER                => MetricType.Gauge
  }

  private def statisticSuffix(statistic: Statistic): String = statistic match {
    case Statistic.COUNT        => "_count"
    case Statistic.TOTAL        => "_total"
    case Statistic.TOTAL_TIME   => "_total_time"
    case Statistic.MAX          => "_max"
    case Statistic.VALUE        => ""
    case Statistic.ACTIVE_TASKS => "_active"
    case Statistic.DURATION     => "_duration"
    case Statistic.UNKNOWN      => ""
  }
}
