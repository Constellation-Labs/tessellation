package io.constellationnetwork.node.shared.logger.sink.clickhouse

import cats.effect._
import cats.effect.std.Queue
import cats.syntax.all._

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

  val createTableDDL: String => String = tableName => s"""CREATE TABLE IF NOT EXISTS $tableName (
       |    timestamp DateTime64(3),
       |    node_id LowCardinality(String),
       |    network_id LowCardinality(String),
       |    log_type LowCardinality(String),
       |    data JSON
       |) ENGINE = MergeTree()
       |PARTITION BY (network_id, toYYYYMM(timestamp))
       |ORDER BY (node_id, timestamp)
       |TTL toDateTime(timestamp) + INTERVAL 90 DAY""".stripMargin

  def make[F[_]: Async](
    config: ClickHouseConfig,
    nodeId: PeerId,
    networkId: AppEnvironment
  ): Resource[F, LogSink[F]] =
    for {
      logger <- Resource.eval(Slf4jLogger.create[F])
      ds <- makeDataSource[F](config)
      _ <- Resource.eval(initTable(ds, config.tableName))
      queue <- Resource.eval(Queue.bounded[F, QueuedEntry](config.maxQueueSize))
      pausedUntil <- Resource.eval(Ref.of[F, Long](0L))
      writer = new BatchWriter[F](ds, config, nodeId, networkId, logger, queue, pausedUntil)
      _ <- startFlusher(queue, writer, config, logger, pausedUntil)
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
      _ <- startFlusher(queue, writer, config, logger, pausedUntil)
    } yield new Impl[F](queue, logger, config.tableName)

  // === DataSource Management ===

  def makeDataSource[F[_]: Async](config: ClickHouseConfig): Resource[F, HikariDataSource] =
    Resource.make(Async[F].blocking {
      val hc = new HConfig()
      hc.setJdbcUrl(s"jdbc:clickhouse:https://${config.host}:${config.port}/${config.database}")
      hc.setUsername(config.user)
      hc.setPassword(config.password)
      hc.setMinimumIdle(2)
      hc.setMaximumPoolSize(10)
      hc.setConnectionTimeout(30000)
      hc.setPoolName("clickhouse-logger-pool")
      hc.setConnectionTestQuery("SELECT 1")
      new HikariDataSource(hc)
    })(ds => Async[F].blocking(ds.close()).handleError(_ => ()))

  private def initTable[F[_]: Async](ds: HikariDataSource, tableName: String): F[Unit] =
    Async[F].blocking {
      val conn = ds.getConnection
      try {
        val stmt = conn.createStatement()
        try stmt.execute(createTableDDL(tableName))
        finally stmt.close()
      } finally conn.close()
    }.void

  // === Background Flusher ===

  private def startFlusher[F[_]: Async](
    queue: Queue[F, QueuedEntry],
    writer: BatchWriter[F],
    config: ClickHouseConfig,
    logger: SelfAwareStructuredLogger[F],
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
