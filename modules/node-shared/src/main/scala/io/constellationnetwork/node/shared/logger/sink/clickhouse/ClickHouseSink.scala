package io.constellationnetwork.node.shared.logger.sink.clickhouse

import cats.effect._
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.logger.{LogEntry, LogSink}
import io.constellationnetwork.schema.peer.PeerId

import com.zaxxer.hikari.{HikariConfig => HConfig, HikariDataSource}
import io.circe.syntax._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** ClickHouse sink with batching, retries, and connection pooling. All complexity is contained here - the rest of the logging system stays
  * simple.
  */
object ClickHouseSink {

  private case class QueuedEntry(entry: LogEntry, retryCount: Int = 0, retryAfter: Long = 0L)

  val createTableDDL: (String, Int) => String = (tableName, retentionPeriodInDays) => s"""CREATE TABLE IF NOT EXISTS $tableName (
       |    timestamp DateTime64(3),
       |    node_id LowCardinality(String),
       |    network_id LowCardinality(String),
       |    log_type LowCardinality(String),
       |    data JSON
       |) ENGINE = MergeTree()
       |PARTITION BY (network_id, toYYYYMM(timestamp))
       |ORDER BY (node_id, timestamp)
       |TTL toDateTime(timestamp) + INTERVAL $retentionPeriodInDays DAY""".stripMargin

  def make[F[_]: Async](
    config: ClickHouseConfig,
    nodeId: PeerId,
    networkId: AppEnvironment
  ): Resource[F, LogSink[F]] =
    for {
      logger <- Resource.eval(Slf4jLogger.create[F])
      ds <- makeDataSource[F](config)
      _ <- Resource.eval(initTable(ds, config.tableName, config.retentionPeriodInDays))
      queue <- Resource.eval(Queue.bounded[F, QueuedEntry](config.maxQueueSize))
      pausedUntil <- Resource.eval(Ref.of[F, Long](0L))
      writer = new BatchWriter[F](ds, config, nodeId, networkId, logger, queue, pausedUntil)
      _ <- startFlusher(queue, writer, config, pausedUntil)
    } yield new Impl[F](queue, logger, config.tableName)

  def makeWithDataSource[F[_]: Async](
    ds: HikariDataSource,
    config: ClickHouseConfig,
    nodeId: PeerId,
    networkId: AppEnvironment,
    logger: SelfAwareStructuredLogger[F]
  ): Resource[F, LogSink[F]] =
    for {
      queue <- Resource.eval(Queue.bounded[F, QueuedEntry](config.maxQueueSize))
      pausedUntil <- Resource.eval(Ref.of[F, Long](0L))
      writer = new BatchWriter[F](ds, config, nodeId, networkId, logger, queue, pausedUntil)
      _ <- startFlusher(queue, writer, config, pausedUntil)
    } yield new Impl[F](queue, logger, config.tableName)

  // === DataSource Management ===

  // ClickHouse JDBC driver does not enforce a default socket-connect timeout, so
  // `new HikariDataSource(hc)` can block indefinitely if the configured host is
  // unreachable. A testnet incident saw .193 silent for 35-45 minutes
  // per restart with no further log lines after "Jar hash" -- the JVM was stuck inside
  // HikariCP pool construction waiting on a TCP SYN-ACK to a dead ClickHouse host.
  // The `handleErrorWith` chains in MetricsFactory + ClickHouseLoggerBundle never
  // fired because there was no error to handle, just a hang.
  //
  // Three layered defenses, each protecting the next inward layer:
  //   1. cats-effect `.timeout(connectTimeout)` on the whole acquire — converts a
  //      hang into a TimeoutException that the outer handleErrorWith can catch.
  //   2. HikariCP `setInitializationFailTimeout(...)` — fast-fail at the pool layer
  //      if the validation query can't establish.
  //   3. Driver-level `socket_timeout` / `connection_timeout` properties — fast-fail
  //      at the actual socket layer.
  //
  // Defaults are intentionally tight (15s connect, 10s socket) because metrics
  // and consensus-logging are best-effort sinks; on testnet, falling back to
  // Prometheus-only is far better than wedging the JVM.
  private val connectTimeout: FiniteDuration = 15.seconds
  private val initFailTimeoutMillis: Long = 10000L
  private val socketTimeoutMillis: Long = 10000L
  private val driverConnectTimeoutMillis: Long = 5000L

  def makeDataSource[F[_]: Async](config: ClickHouseConfig): Resource[F, HikariDataSource] = {
    val jdbcUrl = s"jdbc:clickhouse:${config.protocol}://${config.host}:${config.port}/${config.database}"
    val acquire = Async[F].blocking {
      val hc = new HConfig()
      hc.setJdbcUrl(jdbcUrl)
      hc.setUsername(config.user)
      hc.setPassword(config.password)
      hc.setMinimumIdle(2)
      hc.setMaximumPoolSize(10)
      hc.setConnectionTimeout(30000)
      hc.setInitializationFailTimeout(initFailTimeoutMillis)
      hc.setPoolName("clickhouse-logger-pool")
      hc.setConnectionTestQuery("SELECT 1")
      hc.addDataSourceProperty("socket_timeout", socketTimeoutMillis.toString)
      hc.addDataSourceProperty("connection_timeout", driverConnectTimeoutMillis.toString)
      new HikariDataSource(hc)
    }.timeout(connectTimeout)
      .adaptError(e => new RuntimeException(s"Failed to connect to ClickHouse at $jdbcUrl: ${e.getMessage}", e))
    Resource.make(acquire)(ds => Async[F].blocking(ds.close()).handleError(_ => ()))
  }

  private def initTable[F[_]: Async](ds: HikariDataSource, tableName: String, retentionPeriodInDays: Int): F[Unit] =
    Async[F].blocking {
      val conn = ds.getConnection
      try {
        val stmt = conn.createStatement()
        try stmt.execute(createTableDDL(tableName, retentionPeriodInDays))
        finally stmt.close()
      } finally conn.close()
    }
      .timeout(connectTimeout)
      .void

  // === Background Flusher ===

  private def startFlusher[F[_]: Async](
    queue: Queue[F, QueuedEntry],
    writer: BatchWriter[F],
    config: ClickHouseConfig,
    pausedUntil: Ref[F, Long]
  ): Resource[F, Unit] = {
    val flush: F[Unit] = for {
      now <- Async[F].realTime.map(_.toMillis)
      paused <- pausedUntil.get
      _ <-
        if (now < paused) Async[F].unit
        else
          queue.tryTakeN(Some(config.batchSize)).flatMap { entries =>
            val (ready, notReady) = entries.partition(_.retryAfter <= now)
            notReady.traverse_(e => queue.tryOffer(e)) >>
              writer.writeBatch(ready).whenA(ready.nonEmpty)
          }
    } yield ()

    Spawn[F].background((Temporal[F].sleep(config.flushInterval) >> flush).foreverM).void
  }

  // === Batch Writer ===

  private class BatchWriter[F[_]](
    ds: HikariDataSource,
    config: ClickHouseConfig,
    nodeId: PeerId,
    networkId: AppEnvironment,
    logger: SelfAwareStructuredLogger[F],
    queue: Queue[F, QueuedEntry],
    pausedUntil: Ref[F, Long]
  )(implicit F: Async[F]) {

    private val insertSql =
      s"INSERT INTO ${config.tableName} (timestamp, node_id, network_id, log_type, data) VALUES (now64(3), ?, ?, ?, ?)"

    def writeBatch(entries: List[QueuedEntry]): F[Unit] =
      Resource
        .make(F.blocking(ds.getConnection))(c => F.blocking(c.close()).handleError(_ => ()))
        .flatMap(conn => Resource.make(F.blocking(conn.prepareStatement(insertSql)))(s => F.blocking(s.close()).handleError(_ => ())))
        .use { stmt =>
          F.blocking {
            entries.foreach { qe =>
              stmt.setString(1, nodeId.value.value)
              stmt.setString(2, networkId.toString)
              stmt.setString(3, qe.entry.logType)
              stmt.setString(4, qe.entry.asJson.noSpaces)
              stmt.addBatch()
            }
            stmt.executeBatch()
          }
        }
        .void
        .handleErrorWith(handleWriteError(entries, _))

    private def handleWriteError(entries: List[QueuedEntry], e: Throwable): F[Unit] =
      for {
        _ <- logger.warn(s"ClickHouse write failed: ${e.getMessage}")
        now <- F.realTime.map(_.toMillis)
        _ <- pausedUntil.set(now + config.errorPauseDuration.toMillis)
        _ <- entries.filter(_.retryCount < config.maxRetries).traverse_ { qe =>
          val delay = config.retryBaseDelay.toMillis * (1L << Math.min(qe.retryCount + 1, 20))
          queue.tryOffer(qe.copy(retryCount = qe.retryCount + 1, retryAfter = now + delay))
        }
      } yield ()
  }

  // === Sink Implementation ===

  private class Impl[F[_]: Async](
    queue: Queue[F, QueuedEntry],
    logger: SelfAwareStructuredLogger[F],
    tableName: String
  ) extends LogSink[F] {

    def write(entry: LogEntry): F[Unit] =
      queue.tryOffer(QueuedEntry(entry)).flatMap {
        case true  => Async[F].unit
        case false => logger.warn(s"Log queue full, dropping ${entry.logType} for $tableName")
      }
  }
}
