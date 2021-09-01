package org.tessellation.metrics

import cats.effect.{IO, Sync}
import cats.syntax.all._
import com.google.common.util.concurrent.AtomicDouble
import fs2.Stream
import io.micrometer.core.instrument.Metrics.globalRegistry
import io.micrometer.core.instrument.binder.jvm._
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.{FileDescriptorMetrics, ProcessorMetrics, UptimeMetrics}
import io.micrometer.core.instrument.{Clock, Tag, Timer}
import io.micrometer.prometheus.{PrometheusConfig, PrometheusMeterRegistry}
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.cache.caffeine.CacheMetricsCollector
import org.http4s.metrics.prometheus.Prometheus
import org.joda.time.DateTime
import org.tessellation.metrics.Metric.{Metric, _}

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object Metrics {

  def init(nodeId: String): Stream[IO, Metrics] =
    Stream
      .resource(Prometheus.collectorRegistry[IO])
      .map(
        registry =>
          Metrics(
            registry,
            1,
            scala.concurrent.ExecutionContext.global,
            nodeId
          )
      )

  def apply(
    collectorRegistry: CollectorRegistry,
    periodSeconds: Int = 1,
    unboundedExecutionContext: ExecutionContext,
    nodeID: String
  ): Metrics = new Metrics(collectorRegistry, periodSeconds, unboundedExecutionContext, nodeID)

  val cacheMetrics = new CacheMetricsCollector()
  var isCacheRegistered = false

  def prometheusSetup(collectorRegistry: CollectorRegistry): PrometheusMeterRegistry = {
    val prometheusMeterRegistry =
      new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, collectorRegistry, Clock.SYSTEM)

    if (!isCacheRegistered) { // TODO: use cacheMetrics as a dependency
      collectorRegistry.register(cacheMetrics)
      isCacheRegistered = true
    }
    prometheusMeterRegistry.config().commonTags("application", "Constellation")
    globalRegistry.add(prometheusMeterRegistry)
    io.micrometer.core.instrument.Metrics.globalRegistry.add(prometheusMeterRegistry)

    new JvmMemoryMetrics().bindTo(prometheusMeterRegistry)
    new JvmGcMetrics().bindTo(prometheusMeterRegistry)
    new JvmThreadMetrics().bindTo(prometheusMeterRegistry)
    new UptimeMetrics().bindTo(prometheusMeterRegistry)
    new ProcessorMetrics().bindTo(prometheusMeterRegistry)
    new FileDescriptorMetrics().bindTo(prometheusMeterRegistry)
    new LogbackMetrics().bindTo(prometheusMeterRegistry)
    new ClassLoaderMetrics().bindTo(prometheusMeterRegistry)
    // new DatabaseTableMetrics().bindTo(prometheusMeterRegistry)

    prometheusMeterRegistry
  }
}

class Metrics(
  val collectorRegistry: CollectorRegistry,
  periodSeconds: Int = 1,
  unboundedExecutionContext: ExecutionContext,
  nodeID: String
) extends PeriodicIO("Metrics", unboundedExecutionContext) {

  type TagSeq = Seq[(String, String)]
  val registry = Metrics.prometheusSetup(collectorRegistry)

  val init = for {
    currentTime <- cats.effect.Clock[IO].realTime(MILLISECONDS)
    _ <- updateMetricAsync[IO]("ip", nodeID)
    _ <- updateMetricAsync[IO]("nodeStartTimeMS", currentTime.toString)
  } yield ()
  private val stringMetrics: TrieMap[(String, TagSeq), String] = TrieMap()
  private val longMetrics: TrieMap[(String, TagSeq), AtomicLong] = TrieMap()
  private val doubleMetrics: TrieMap[(String, TagSeq), AtomicDouble] = TrieMap()

  init.unsafeRunSync

  def updateMetric(key: Metric, value: Double): Unit =
    updateMetric(key, value, Seq.empty)

  def updateMetric(key: Metric, value: Int): Unit =
    updateMetric(key, value, Seq.empty)

  def updateMetric(key: Metric, value: Long): Unit =
    updateMetric(key, value, Seq.empty)

  def incrementMetric(key: Metric): Unit = incrementMetric(key, Seq.empty)

  def startTimer: Timer.Sample = Timer.start()

  def stopTimer(key: Metric, timer: Timer.Sample): Unit = {
    timer.stop(Timer.builder(key).register(registry))
    ()
  }

  def updateMetricAsync[F[_]: Sync](key: Metric, value: Double): F[Unit] =
    updateMetricAsync(key, value, Seq.empty)

  def updateMetricAsync[F[_]: Sync](key: Metric, value: Double, tags: TagSeq): F[Unit] =
    Sync[F].delay(updateMetric(key, value, tags))

  def updateMetric(key: Metric, value: Double, tags: TagSeq): Unit =
    doubleMetrics.getOrElseUpdate((key, tags), gaugedAtomicDouble(key, tags)).set(value)

  private def gaugedAtomicDouble(key: Metric, tags: TagSeq): AtomicDouble =
    registry.gauge(s"dag_$key", tags.toMicrometer(key), new AtomicDouble(0d))

  def updateMetricAsync[F[_]: Sync](key: Metric, value: Int): F[Unit] =
    updateMetricAsync(key, value, Seq.empty)

  def updateMetricAsync[F[_]: Sync](key: Metric, value: Int, tags: TagSeq): F[Unit] =
    Sync[F].delay(updateMetric(key, value, tags))

  def updateMetric(key: Metric, value: Int, tags: TagSeq): Unit =
    updateMetric(key, value.toLong, tags)

  def incrementMetricAsync[F[_]: Sync](key: Metric): F[Unit] = incrementMetricAsync(key, Seq.empty)

  def incrementMetricAsync[F[_]: Sync](key: Metric, tags: TagSeq): F[Unit] = Sync[F].delay(incrementMetric(key, tags))

  def incrementMetricAsync[F[_]: Sync](key: Metric, either: Either[Any, Any]): F[Unit] =
    incrementMetricAsync(key, either, Seq.empty)

  def incrementMetricAsync[F[_]: Sync](key: Metric, either: Either[Any, Any], tags: TagSeq): F[Unit] =
    Sync[F].delay(either match {
      case Left(_)  => incrementMetric(key.failure, tags)
      case Right(_) => incrementMetric(key.success, tags)
    })

  def incrementMetric(key: Metric, tags: TagSeq): Unit = {
    registry.counter(s"dag_$key", tags.toMicrometer(key)).increment()
    longMetrics.getOrElseUpdate((key, tags), new AtomicLong(0)).incrementAndGet()
    ()
  }

  /**
    * Converts counter metrics to string for export / display
    *
    * @return : Key value map of all metrics
    */
  def getSimpleMetrics: Map[String, String] =
    formatSimpleMetrics(stringMetrics) ++
      formatSimpleMetrics(longMetrics) ++
      formatSimpleMetrics(doubleMetrics)

  private def formatSimpleMetrics[T](metrics: TrieMap[(String, TagSeq), T]): Map[String, String] =
    metrics.toList.filter { case ((_, tags), _) => tags.isEmpty }.map {
      case ((k, _), v) => (k, v.toString)
    }.toMap

  def getTaggedMetrics: Map[String, Map[String, String]] =
    formatTaggedMetric(stringMetrics) ++
      formatTaggedMetric(longMetrics) ++
      formatTaggedMetric(doubleMetrics)

  private def formatTaggedMetric[T](metrics: TrieMap[(String, TagSeq), T]): Map[String, Map[String, String]] =
    metrics.toList.filter { case ((_, tags), _) => tags.nonEmpty }.groupBy { case ((k, _), _) => k }.map {
      case (k, metrics) =>
        (k, metrics.map {
          case ((_, tags), v) =>
            (tags.map(t => s"${t._1}=${t._2}").mkString(","), v.toString)
        }.toMap)
    }

  def getCountMetric(key: String): Option[Long] =
    longMetrics.get((key, Seq.empty)).map(_.get())

  def getCountMetric(key: String, tags: Seq[(String, String)]): Option[Long] =
    longMetrics.get((key, tags)).map(_.get())

  /**
    * Recalculates window based / periodic metrics
    */
  override def trigger(): IO[Unit] =
    updatePeriodicMetrics()

  private def updatePeriodicMetrics(): IO[Unit] =
    //    updateBalanceMetrics >>
    //      updateBlacklistedAddressesMetrics() >>
    //      updateTransactionAcceptedMetrics() >>
    //      updateObservationServiceMetrics() >>
    updateMetricAsync[IO]("nodeCurrentTimeMS", System.currentTimeMillis().toString) >>
      updateMetricAsync[IO]("nodeCurrentDate", new DateTime().toString()) >>
      updateMetricAsync[IO]("metricsRound", executionNumber.get())

  def updateMetricAsync[F[_]: Sync](key: Metric, value: String): F[Unit] =
    Sync[F].delay(updateMetric(key, value))

  def updateMetric(key: Metric, value: String): Unit =
    updateMetric(key, value, Seq.empty)

  def updateMetric(key: Metric, value: String, tags: TagSeq): Unit =
    stringMetrics((key, tags)) = value

  def updateMetricAsync[F[_]: Sync](key: Metric, value: Long): F[Unit] =
    updateMetricAsync(key, value, Seq.empty)

  def updateMetricAsync[F[_]: Sync](key: Metric, value: Long, tags: TagSeq): F[Unit] =
    Sync[F].delay(updateMetric(key, value, tags))

  def updateMetric(key: Metric, value: Long, tags: TagSeq): Unit =
    longMetrics.getOrElseUpdate((key, tags), gaugedAtomicLong(key, tags)).set(value)
  //      dao.addressService.size.flatMap(size => updateMetricAsync[IO]("addressCount", size)) >>
  //      updateMetricAsync[IO]("channelCount", dao.threadSafeMessageMemPool.activeChannels.size) >>
  //      updateTransactionServiceMetrics()

  implicit class TagSeqOps(val tagSeq: TagSeq) {

    def toMicrometer(key: String): java.util.List[Tag] =
      (Tag.of("metric", key) +: tagSeq.map { case (k, v) => Tag.of(k, v) }).asJava
  }

  private def gaugedAtomicLong(key: Metric, tags: TagSeq): AtomicLong =
    registry.gauge(s"dag_$key", tags.toMicrometer(key), new AtomicLong(0L))

  schedule(0.seconds, periodSeconds.seconds)
}
