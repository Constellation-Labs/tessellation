package io.constellationnetwork.node.shared.infrastructure.metrics

import cats.effect._
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics._
import io.constellationnetwork.node.shared.logger.sink.clickhouse.{ClickHouseConfig, ClickHouseSink}
import io.constellationnetwork.schema.peer.PeerId

import com.zaxxer.hikari.HikariDataSource
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

sealed trait MetricType
object MetricType {
  case object Gauge extends MetricType
  case object Counter extends MetricType
  case object Histogram extends MetricType
  case object Distribution extends MetricType
}

case class MetricEntry(
  metricName: String,
  metricType: MetricType,
  value: Double,
  tags: TagSeq
)

trait MetricsSink[F[_]] {
  def write(entry: MetricEntry): F[Unit]
}

object ClickHouseMetricsSink {

  private case class QueuedMetric(entry: MetricEntry, retryCount: Int = 0, retryAfter: Long = 0L)

  val createTableDDL: (String, Int) => String = (tableName, retentionPeriodInDays) => s"""CREATE TABLE IF NOT EXISTS $tableName (
       |    timestamp DateTime64(3),
       |    node_id LowCardinality(String),
       |    network_id LowCardinality(String),
       |    metric_name LowCardinality(String),
       |    metric_type Enum8('gauge' = 1, 'counter' = 2, 'histogram' = 3, 'distribution' = 4),
       |    value Float64,
       |    tags Map(String, String)
       |) ENGINE = MergeTree()
       |PARTITION BY (network_id, toYYYYMM(timestamp))
       |ORDER BY (node_id, metric_name, timestamp)
       |TTL toDateTime(timestamp) + INTERVAL $retentionPeriodInDays DAY""".stripMargin

  def make[F[_]: Async](
    config: ClickHouseConfig,
    nodeId: PeerId,
    networkId: AppEnvironment
  ): Resource[F, MetricsSink[F]] =
    for {
      logger <- Resource.eval(Slf4jLogger.create[F])
      ds <- ClickHouseSink.makeDataSource[F](config)
      _ <- Resource.eval(initTable(ds, config.tableName, config.retentionPeriodInDays))
      queue <- Resource.eval(Queue.bounded[F, QueuedMetric](config.maxQueueSize))
      pausedUntil <- Resource.eval(Ref.of[F, Long](0L))
      writer = new BatchWriter[F](ds, config, nodeId, networkId, logger, queue, pausedUntil)
      _ <- startFlusher(queue, writer, config, pausedUntil, logger)
    } yield new Impl[F](queue, logger, config.tableName)

  def makeWithDataSource[F[_]: Async](
    ds: HikariDataSource,
    config: ClickHouseConfig,
    nodeId: PeerId,
    networkId: AppEnvironment,
    logger: SelfAwareStructuredLogger[F]
  ): Resource[F, MetricsSink[F]] =
    for {
      _ <- Resource.eval(initTable(ds, config.tableName, config.retentionPeriodInDays))
      queue <- Resource.eval(Queue.bounded[F, QueuedMetric](config.maxQueueSize))
      pausedUntil <- Resource.eval(Ref.of[F, Long](0L))
      writer = new BatchWriter[F](ds, config, nodeId, networkId, logger, queue, pausedUntil)
      _ <- startFlusher(queue, writer, config, pausedUntil, logger)
    } yield new Impl[F](queue, logger, config.tableName)

  // 15s timeout matches ClickHouseSink.connectTimeout — guards against `getConnection`
  // or DDL execution stalling indefinitely if ClickHouse becomes unreachable between
  // pool init and table creation.
  private def initTable[F[_]: Async](ds: HikariDataSource, tableName: String, retentionPeriodInDays: Int): F[Unit] =
    Async[F].blocking {
      val conn = ds.getConnection
      try {
        val stmt = conn.createStatement()
        try stmt.execute(createTableDDL(tableName, retentionPeriodInDays))
        finally stmt.close()
      } finally conn.close()
    }
      .timeout(15.seconds)
      .void

  private def startFlusher[F[_]: Async](
    queue: Queue[F, QueuedMetric],
    writer: BatchWriter[F],
    config: ClickHouseConfig,
    pausedUntil: Ref[F, Long],
    logger: SelfAwareStructuredLogger[F]
  ): Resource[F, Unit] = {
    val flush: F[Unit] = for {
      now <- Async[F].realTime.map(_.toMillis)
      paused <- pausedUntil.get
      _ <-
        if (now < paused) Async[F].unit
        else
          queue.tryTakeN(Some(config.batchSize)).flatMap { entries =>
            val (ready, notReady) = entries.partition(_.retryAfter <= now)
            notReady.traverse_(e =>
              queue.tryOffer(e).flatMap {
                case true  => Async[F].unit
                case false => logger.warn(s"Metrics queue full, dropping retryable metric: ${e.entry.metricName}")
              }
            ) >>
              writer.writeBatch(ready).whenA(ready.nonEmpty)
          }
    } yield ()

    Spawn[F].background((Temporal[F].sleep(config.flushInterval) >> flush).foreverM).void
  }

  private class BatchWriter[F[_]](
    ds: HikariDataSource,
    config: ClickHouseConfig,
    nodeId: PeerId,
    networkId: AppEnvironment,
    logger: SelfAwareStructuredLogger[F],
    queue: Queue[F, QueuedMetric],
    pausedUntil: Ref[F, Long]
  )(implicit F: Async[F]) {

    private val insertSql =
      s"INSERT INTO ${config.tableName} (timestamp, node_id, network_id, metric_name, metric_type, value, tags) VALUES (now64(3), ?, ?, ?, ?, ?, ?)"

    private def metricTypeToString(mt: MetricType): String = mt match {
      case MetricType.Gauge        => "gauge"
      case MetricType.Counter      => "counter"
      case MetricType.Histogram    => "histogram"
      case MetricType.Distribution => "distribution"
    }

    private def tagsToMapString(tags: TagSeq): String = {
      val pairs = tags.map { case (k, v) => s"'${escapeSingleQuotes(k.value)}':'${escapeSingleQuotes(v)}'" }.mkString(",")
      s"{$pairs}"
    }

    private def escapeSingleQuotes(s: String): String =
      s.replace("'", "''")

    def writeBatch(entries: List[QueuedMetric]): F[Unit] =
      Resource
        .make(F.blocking(ds.getConnection))(c => F.blocking(c.close()).handleError(_ => ()))
        .flatMap(conn => Resource.make(F.blocking(conn.prepareStatement(insertSql)))(s => F.blocking(s.close()).handleError(_ => ())))
        .use { stmt =>
          F.blocking {
            entries.foreach { qm =>
              stmt.setString(1, nodeId.value.value)
              stmt.setString(2, networkId.toString)
              stmt.setString(3, qm.entry.metricName)
              stmt.setString(4, metricTypeToString(qm.entry.metricType))
              stmt.setDouble(5, qm.entry.value)
              stmt.setString(6, tagsToMapString(qm.entry.tags))
              stmt.addBatch()
            }
            stmt.executeBatch()
          }
        }
        .void
        .handleErrorWith(handleWriteError(entries, _))

    private def handleWriteError(entries: List[QueuedMetric], e: Throwable): F[Unit] =
      for {
        _ <- logger.warn(s"ClickHouse metrics write failed: ${e.getMessage}")
        now <- F.realTime.map(_.toMillis)
        _ <- pausedUntil.set(now + config.errorPauseDuration.toMillis)
        _ <- entries.filter(_.retryCount < config.maxRetries).traverse_ { qm =>
          val delay = config.retryBaseDelay.toMillis * (1L << Math.min(qm.retryCount + 1, 20))
          queue.tryOffer(qm.copy(retryCount = qm.retryCount + 1, retryAfter = now + delay)).flatMap {
            case true  => F.unit
            case false => logger.warn(s"Metrics queue full, dropping retryable metric: ${qm.entry.metricName} (retry ${qm.retryCount + 1})")
          }
        }
      } yield ()
  }

  private class Impl[F[_]: Async](
    queue: Queue[F, QueuedMetric],
    logger: SelfAwareStructuredLogger[F],
    tableName: String
  ) extends MetricsSink[F] {

    def write(entry: MetricEntry): F[Unit] =
      queue.tryOffer(QueuedMetric(entry)).flatMap {
        case true  => Async[F].unit
        case false => logger.warn(s"Metrics queue full, dropping ${entry.metricName} for $tableName")
      }
  }
}
