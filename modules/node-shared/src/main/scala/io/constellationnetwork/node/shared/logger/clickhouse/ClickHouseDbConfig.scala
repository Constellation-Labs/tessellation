package io.constellationnetwork.node.shared.logger.clickhouse

import io.constellationnetwork.node.shared.config.types.ClickHouseAppConfig

case class ClickHouseDbConfig(
  host: String,
  port: Int,
  database: String,
  user: String,
  password: String,
  tableName: String
)

object ClickHouseDbConfig {

  sealed trait ConfigValidationError extends Throwable
  case class InvalidHost(value: String) extends ConfigValidationError {
    override def getMessage: String = s"Invalid CLICKHOUSE_HOST: '$value'"
  }
  case class InvalidPort(value: Int) extends ConfigValidationError {
    override def getMessage: String = s"Invalid CLICKHOUSE_PORT: $value. Must be between 1 and 65535."
  }
  case class InvalidIdentifier(field: String, value: String) extends ConfigValidationError {
    override def getMessage: String = s"Invalid $field: '$value'. Must be alphanumeric with underscores only."
  }
  case object MissingConfig extends ConfigValidationError {
    override def getMessage: String = "Missing required ClickHouse configuration"
  }

  private val hostnamePattern = "^[a-zA-Z0-9]([a-zA-Z0-9\\-\\.]{0,253}[a-zA-Z0-9])?$".r
  private val ipv4Pattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$".r
  private val ipv6Pattern = "^\\[?([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\\]?$".r
  private val identifierPattern = "^[a-zA-Z_][a-zA-Z0-9_]{0,63}$".r

  def fromAppConfig(config: ClickHouseAppConfig): Either[ConfigValidationError, Option[ClickHouseDbConfig]] = {
    val allMissing = config.host.isEmpty &&
      config.user.isEmpty &&
      config.password.isEmpty &&
      config.tableName.isEmpty

    if (allMissing) Right(None)
    else {
      for {
        host <- config.host.toRight(MissingConfig).flatMap(validateHost)
        port <- config.port.toRight(MissingConfig).flatMap(validatePort)
        database <- config.database.toRight(MissingConfig).flatMap(validateIdentifier("database", _))
        user <- config.user.toRight(MissingConfig)
        tableName <- config.tableName.toRight(MissingConfig).flatMap(validateIdentifier("tableName", _))
        password <- config.password.toRight(MissingConfig)
      } yield
        Some(
          ClickHouseDbConfig(
            host = host,
            port = port,
            database = database,
            user = user,
            password = password,
            tableName = tableName
          )
        )
    }
  }

  private def validateHost(host: String): Either[ConfigValidationError, String] =
    if (hostnamePattern.matches(host) || ipv4Pattern.matches(host) || ipv6Pattern.matches(host)) Right(host)
    else Left(InvalidHost(host))

  private def validatePort(port: Int): Either[ConfigValidationError, Int] =
    if (port >= 1 && port <= 65535) Right(port)
    else Left(InvalidPort(port))

  private def validateIdentifier(field: String, value: String): Either[ConfigValidationError, String] =
    if (identifierPattern.matches(value)) Right(value)
    else Left(InvalidIdentifier(field, value))
}
