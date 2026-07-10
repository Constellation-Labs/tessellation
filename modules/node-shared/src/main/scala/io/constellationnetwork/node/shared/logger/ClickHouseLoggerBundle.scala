package io.constellationnetwork.node.shared.logger

import cats.effect._
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.node.shared.config.types.ClickHouseAppConfig
import io.constellationnetwork.node.shared.logger.sink.clickhouse._
import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Creates a logger bundle that writes to ClickHouse. Falls back to Slf4j if ClickHouse is not configured.
  */
object ClickHouseLoggerBundle {

  case class ConfigError(error: ClickHouseConfig.ValidationError) extends Exception(error.getMessage)
  case class ConnectionError(cause: Throwable) extends Exception(cause.getMessage, cause)
  case object NotConfigured extends Exception("ClickHouse not configured")

  def make[F[_]: Async](
    nodeId: PeerId,
    networkId: AppEnvironment,
    appConfig: ClickHouseAppConfig
  ): Resource[F, LoggerBundle[F]] =
    for {
      maybeConfig <- Resource.eval(
        Async[F].fromEither(ClickHouseConfig.makeLogConfig(appConfig).leftMap(ConfigError))
      )
      config <- maybeConfig match {
        case Some(c) => Resource.pure[F, ClickHouseConfig](c)
        case None    => Resource.raiseError[F, ClickHouseConfig, Throwable](NotConfigured)
      }
      bundle <- makeWithConfig[F](config, nodeId, networkId)
    } yield bundle

  def makeWithConfig[F[_]: Async](
    config: ClickHouseConfig,
    nodeId: PeerId,
    networkId: AppEnvironment
  ): Resource[F, LoggerBundle[F]] =
    for {
      slf4j <- Resource.eval(Slf4jLogger.create[F])
      ds <- ClickHouseSink.makeDataSource[F](config).adaptError { case e => ConnectionError(e) }

      // Create tables
      _ <- Resource.eval(initTables(ds, config))

      // Create general log sink and app logger
      sink <- ClickHouseSink.makeWithDataSource[F](ds, config, nodeId, networkId, slf4j)
      (appLogger, ctxRef) <- Resource.eval(AppLogger.make[F](sink))

      // Create consensus logger (separate table)
      consensusLogger <- ClickHouseConsensusLogger.make[F](
        ds,
        s"${config.tableName}_consensus",
        nodeId,
        networkId,
        slf4j,
        config.maxQueueSize,
        ctxRef
      )

      // Forward logback events (captured by ClickHouseLogbackAppender) into the
      // same logs table via the general appLogger -> sink path.
      _ <- startLogBridgeDrain[F](appLogger, config)
    } yield LoggerBundle(appLogger, consensusLogger)

  // The logback appender enqueues into the process-wide LogBridge; this fiber is the only consumer.
  // It writes through appLogger so bridged WARN/ERROR rows reuse the existing batching/retry sink
  // and land in the same `logsTableName` table (log_type = WARN/ERROR).
  private def startLogBridgeDrain[F[_]: Async](
    appLogger: AppLogger[F],
    config: ClickHouseConfig
  ): Resource[F, Unit] = {
    val step = for {
      _ <- Temporal[F].sleep(config.flushInterval)
      batch <- Async[F].delay(LogBridge.drain(config.batchSize))
      _ <- batch.traverse_(entry => appLogger.log(entry.level, entry))
    } yield ()

    Spawn[F].background(step.foreverM).void
  }

  // 15s timeout matches ClickHouseSink.connectTimeout — guards against `getConnection`
  // or DDL execution stalling indefinitely.
  private def initTables[F[_]: Async](
    ds: com.zaxxer.hikari.HikariDataSource,
    config: ClickHouseConfig
  ): F[Unit] =
    Async[F].blocking {
      val conn = ds.getConnection
      try {
        val stmt = conn.createStatement()
        try {
          stmt.execute(ClickHouseSink.createTableDDL(config.tableName, config.retentionPeriodInDays))
          stmt.execute(ClickHouseConsensusLogger.createTableDDL(s"${config.tableName}_consensus"))
          ()
        } finally stmt.close()
      } finally conn.close()
    }
}
