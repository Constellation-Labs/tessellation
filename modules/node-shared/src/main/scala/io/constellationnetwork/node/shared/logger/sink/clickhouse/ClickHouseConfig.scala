package io.constellationnetwork.node.shared.logger.sink.clickhouse

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.ClickHouseAppConfig

case class ClickHouseConfig(
  host: String,
  port: Int,
  database: String,
  user: String,
  password: String,
  tableName: String,
  retentionPeriodInDays: Int,
  protocol: String = "https",
  // Batching settings
  maxQueueSize: Int = 10000,
  batchSize: Int = 100,
  flushInterval: FiniteDuration = 5.seconds,
  // Retry settings
  maxRetries: Int = 3,
  retryBaseDelay: FiniteDuration = 1.second,
  errorPauseDuration: FiniteDuration = 30.seconds
)

object ClickHouseConfig {

  sealed trait ValidationError extends Throwable {
    def message: String
    override def getMessage: String = message
  }

  case class InvalidHost(value: String) extends ValidationError {
    val message = s"Invalid host: '$value'"
  }
  case class InvalidPort(value: Int) extends ValidationError {
    val message = s"Invalid port: $value (must be 1-65535)"
  }
  case class InvalidIdentifier(field: String, value: String) extends ValidationError {
    val message = s"Invalid $field: '$value' (must be alphanumeric with underscores)"
  }
  case object MissingConfig extends ValidationError {
    val message = "Missing required ClickHouse configuration"
  }

  private val hostnamePattern = "^[a-zA-Z0-9]([a-zA-Z0-9\\-\\.]{0,253}[a-zA-Z0-9])?$".r
  private val ipv4Pattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$".r
  private val ipv6Pattern = "^\\[?([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\\]?$".r
  private val identifierPattern = "^[a-zA-Z_][a-zA-Z0-9_]{0,63}$".r

  def makeLogConfig(config: ClickHouseAppConfig): Either[ValidationError, Option[ClickHouseConfig]] = {
    val allMissing = config.host.isEmpty && config.user.isEmpty &&
      config.password.isEmpty && config.logsTableName.isEmpty

    if (allMissing) Right(None)
    else
      for {
        host <- config.host.toRight(MissingConfig).flatMap(validateHost)
        port <- config.port.toRight(MissingConfig).flatMap(validatePort)
        database <- config.database.toRight(MissingConfig).flatMap(validateId("database", _))
        user <- config.user.toRight(MissingConfig)
        password <- config.password.toRight(MissingConfig)
        tableName <- config.logsTableName.toRight(MissingConfig).flatMap(validateId("logsTableName", _))
      } yield
        Some(
          ClickHouseConfig(
            host = host,
            port = port,
            database = database,
            user = user,
            password = password,
            tableName = tableName,
            retentionPeriodInDays = config.retentionPeriodInDays,
            protocol = config.protocol.getOrElse("https"),
            maxQueueSize = config.maxQueueSize,
            batchSize = config.batchSize,
            flushInterval = config.flushInterval,
            maxRetries = config.maxRetries,
            retryBaseDelay = config.retryBaseDelay,
            errorPauseDuration = config.errorPauseDuration
          )
        )
  }

  def makeMetricsConfig(config: ClickHouseAppConfig): Either[ValidationError, Option[ClickHouseConfig]] = {
    val allMissing = config.host.isEmpty && config.user.isEmpty &&
      config.password.isEmpty && config.metricsTableName.isEmpty

    if (allMissing) Right(None)
    else
      for {
        host <- config.host.toRight(MissingConfig).flatMap(validateHost)
        port <- config.port.toRight(MissingConfig).flatMap(validatePort)
        database <- config.database.toRight(MissingConfig).flatMap(validateId("database", _))
        user <- config.user.toRight(MissingConfig)
        password <- config.password.toRight(MissingConfig)
        tableName <- config.metricsTableName.toRight(MissingConfig).flatMap(validateId("metricsTableName", _))
      } yield
        Some(
          ClickHouseConfig(
            host = host,
            port = port,
            database = database,
            user = user,
            password = password,
            tableName = tableName,
            retentionPeriodInDays = config.retentionPeriodInDays,
            protocol = config.protocol.getOrElse("https"),
            maxQueueSize = config.maxQueueSize,
            batchSize = config.batchSize,
            flushInterval = config.flushInterval,
            maxRetries = config.maxRetries,
            retryBaseDelay = config.retryBaseDelay,
            errorPauseDuration = config.errorPauseDuration
          )
        )
  }

  private def validateHost(h: String): Either[ValidationError, String] =
    if (hostnamePattern.matches(h) || ipv4Pattern.matches(h) || ipv6Pattern.matches(h)) Right(h)
    else Left(InvalidHost(h))

  private def validatePort(p: Int): Either[ValidationError, Int] =
    if (p >= 1 && p <= 65535) Right(p) else Left(InvalidPort(p))

  private def validateId(field: String, v: String): Either[ValidationError, String] =
    if (identifierPattern.matches(v)) Right(v) else Left(InvalidIdentifier(field, v))
}
