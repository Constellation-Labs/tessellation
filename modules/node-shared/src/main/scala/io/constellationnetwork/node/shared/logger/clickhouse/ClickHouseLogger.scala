package io.constellationnetwork.node.shared.logger.clickhouse

import cats.effect._
import cats.effect.std.Queue
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.ClickHouseAppConfig
import io.constellationnetwork.node.shared.logger.DatabaseLogger
import io.constellationnetwork.schema.peer.PeerId

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object ClickHouseLogger {

  private case class SimpleLog(message: String)
  private case class LogEntry(logType: String, data: Json, retryCount: Int = 0, retryAfter: Long = 0L)

  case class ConfigError(error: ClickHouseDbConfig.ConfigValidationError) extends Exception(error.getMessage)
  case class ConnectionError(cause: Throwable) extends Exception(cause.getMessage, cause)
  case object NotConfigured extends Exception("ClickHouse not configured")

  private val MaxBackoffMillis = 3600000L // 1 hour cap

  def make[F[_]: Async](
    nodeId: PeerId,
    networkId: AppEnvironment,
    appConfig: ClickHouseAppConfig
  ): Resource[F, DatabaseLogger[F]] =
    for {
      maybeConfig <- Resource.eval(
        Async[F].fromEither(
          ClickHouseDbConfig.fromAppConfig(appConfig).leftMap(ConfigError)
        )
      )
      config <- maybeConfig match {
        case Some(c) => Resource.pure[F, ClickHouseDbConfig](c)
        case None    => Resource.raiseError[F, ClickHouseDbConfig, Throwable](NotConfigured)
      }
      logger <- Resource.eval(Slf4jLogger.create[F])
      dataSource <- makeDataSource[F](config).adaptError { case e => ConnectionError(e) }
      queue <- Resource.eval(Queue.bounded[F, LogEntry](appConfig.maxQueueSize))
      pausedUntil <- Resource.eval(Ref.of[F, Long](0L))
      droppedCount <- Resource.eval(Ref.of[F, Long](0L))
      _ <- startFlusher(queue, dataSource, config, nodeId, networkId, appConfig, logger, pausedUntil, droppedCount)
    } yield new Impl[F](queue, dataSource, config, appConfig, logger, droppedCount)

  private def makeDataSource[F[_]: Async](config: ClickHouseDbConfig): Resource[F, HikariDataSource] =
    Resource.make(
      Async[F].blocking {
        val hc = new HikariConfig()
        hc.setJdbcUrl(s"jdbc:clickhouse:https://${config.host}:${config.port}/${config.database}")
        hc.setUsername(config.user)
        hc.setPassword(config.password)
        hc.setMinimumIdle(2)
        hc.setMaximumPoolSize(10)
        hc.setConnectionTimeout(30000)
        hc.setPoolName("clickhouse-logger-pool")
        hc.setConnectionTestQuery("SELECT 1")
        new HikariDataSource(hc)
      }
    )(ds => Async[F].blocking(ds.close()).handleError(_ => ()))

  private def startFlusher[F[_]: Async](
    queue: Queue[F, LogEntry],
    dataSource: HikariDataSource,
    config: ClickHouseDbConfig,
    nodeId: PeerId,
    networkId: AppEnvironment,
    appConfig: ClickHouseAppConfig,
    logger: SelfAwareStructuredLogger[F],
    pausedUntil: Ref[F, Long],
    droppedCount: Ref[F, Long]
  ): Resource[F, Unit] = {

    def requeue(entry: LogEntry): F[Unit] =
      queue.tryOffer(entry).flatMap {
        case true => Async[F].unit
        case false =>
          droppedCount.update(_ + 1) >>
            logger.warn(s"Failed to requeue ${entry.logType} entry (retry ${entry.retryCount}), queue full")
      }

    def flush: F[Unit] =
      for {
        now <- Async[F].realTime.map(_.toMillis)
        paused <- pausedUntil.get
        _ <-
          if (now < paused) Async[F].unit
          else
            queue.tryTakeN(Some(appConfig.batchSize)).flatMap { entries =>
              val (ready, notReady) = entries.partition(_.retryAfter <= now)
              notReady.traverse_(requeue) >>
                (if (ready.nonEmpty)
                   writeBatch(ready, dataSource, config, nodeId, networkId, appConfig, logger, queue, pausedUntil, droppedCount)
                 else Async[F].unit)
            }
      } yield ()

    Spawn[F]
      .background(
        (Temporal[F].sleep(appConfig.flushInterval) >> flush).foreverM
      )
      .void
  }

  private def calculateBackoff(retryCount: Int, baseDelayMillis: Long): Long = {
    val exponent = Math.min(retryCount, 20) // Cap exponent to prevent overflow
    val delay = baseDelayMillis * (1L << exponent)
    Math.min(delay, MaxBackoffMillis)
  }

  private def writeBatch[F[_]: Async](
    entries: List[LogEntry],
    dataSource: HikariDataSource,
    config: ClickHouseDbConfig,
    nodeId: PeerId,
    networkId: AppEnvironment,
    appConfig: ClickHouseAppConfig,
    logger: SelfAwareStructuredLogger[F],
    queue: Queue[F, LogEntry],
    pausedUntil: Ref[F, Long],
    droppedCount: Ref[F, Long]
  ): F[Unit] =
    Resource
      .make(Async[F].blocking(dataSource.getConnection))(c => Async[F].blocking(c.close()).handleError(_ => ()))
      .flatMap { conn =>
        // Table name is validated by identifierPattern in ClickHouseDbConfig
        val sql = s"INSERT INTO ${config.tableName} (timestamp, node_id, network_id, log_type, data) VALUES (now64(3), ?, ?, ?, ?)"
        Resource.make(Async[F].blocking(conn.prepareStatement(sql)))(s => Async[F].blocking(s.close()).handleError(_ => ()))
      }
      .use { stmt =>
        Async[F].blocking {
          entries.foreach { entry =>
            stmt.setString(1, nodeId.toString)
            stmt.setString(2, networkId.toString)
            stmt.setString(3, entry.logType)
            stmt.setString(4, entry.data.noSpaces)
            stmt.addBatch()
          }
          stmt.executeBatch()
        }
      }
      .void
      .handleErrorWith { e =>
        for {
          _ <- logger.warn(s"Failed to write to ClickHouse: ${e.getMessage}")
          now <- Async[F].realTime.map(_.toMillis)
          _ <- pausedUntil.set(now + appConfig.errorPauseDuration.toMillis)
          retryable = entries.filter(_.retryCount < appConfig.maxRetries)
          dropped = entries.size - retryable.size
          _ <-
            if (dropped > 0) {
              droppedCount.update(_ + dropped) >>
                logger.warn(s"Dropping $dropped entries after exhausting retries")
            } else Async[F].unit
          _ <- retryable.traverse_ { entry =>
            val newRetryCount = entry.retryCount + 1
            val delay = calculateBackoff(newRetryCount, appConfig.retryBaseDelay.toMillis)
            queue.tryOffer(entry.copy(retryCount = newRetryCount, retryAfter = now + delay)).flatMap {
              case true => Async[F].unit
              case false =>
                droppedCount.update(_ + 1) >>
                  logger.warn(s"Failed to requeue ${entry.logType} for retry, queue full")
            }
          }
        } yield ()
      }

  private class Impl[F[_]: Async](
    queue: Queue[F, LogEntry],
    dataSource: HikariDataSource,
    config: ClickHouseDbConfig,
    appConfig: ClickHouseAppConfig,
    logger: SelfAwareStructuredLogger[F],
    droppedCount: Ref[F, Long]
  ) extends DatabaseLogger[F] {

    def createLogsTable(): F[Unit] =
      Resource
        .make(Async[F].blocking(dataSource.getConnection))(c => Async[F].blocking(c.close()).handleError(_ => ()))
        .flatMap { conn =>
          Resource.make(Async[F].blocking(conn.createStatement()))(s => Async[F].blocking(s.close()).handleError(_ => ()))
        }
        .use { stmt =>
          Async[F].blocking {
            val retentionDays = Math.max(1, appConfig.retentionPeriodInDays)
            stmt.execute(
              s"""
                 |CREATE TABLE IF NOT EXISTS ${config.tableName} (
                 |    timestamp DateTime64(3),
                 |    node_id LowCardinality(String),
                 |    network_id LowCardinality(String),
                 |    log_type LowCardinality(String),
                 |    data JSON
                 |) ENGINE = MergeTree()
                 |PARTITION BY (network_id, toYYYYMM(timestamp))
                 |ORDER BY (node_id, timestamp)
                 |TTL toDateTime(timestamp) + INTERVAL $retentionDays DAY
                 |""".stripMargin
            )
            ()
          }
        }

    private def enqueue[T: Encoder](logType: String, data: T): F[Unit] =
      queue.tryOffer(LogEntry(logType, data.asJson)).flatMap {
        case true => Async[F].unit
        case false =>
          droppedCount.update(_ + 1) >>
            logger.warn(s"Log queue full, dropping $logType")
      }

    def log[T: Encoder](logType: String, data: T): F[Unit] = enqueue(logType, data)
    def info[T: Encoder](data: T): F[Unit] = enqueue("INFO", data)
    def error[T: Encoder](data: T): F[Unit] = enqueue("ERROR", data)
    def warn[T: Encoder](data: T): F[Unit] = enqueue("WARN", data)
    def debug[T: Encoder](data: T): F[Unit] = enqueue("DEBUG", data)

    def info(message: String): F[Unit] = enqueue("INFO", SimpleLog(message))
    def error(message: String): F[Unit] = enqueue("ERROR", SimpleLog(message))
    def warn(message: String): F[Unit] = enqueue("WARN", SimpleLog(message))
    def debug(message: String): F[Unit] = enqueue("DEBUG", SimpleLog(message))
  }
}
