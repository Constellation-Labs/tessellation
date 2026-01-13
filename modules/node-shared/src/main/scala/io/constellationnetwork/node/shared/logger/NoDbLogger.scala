package io.constellationnetwork.node.shared.logger

import cats.effect.{Async, Resource}
import cats.syntax.all._

import io.circe.Encoder
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object NoDbLogger {
  def make[F[_]: Async]: Resource[F, DatabaseLogger[F]] =
    Resource.eval(Slf4jLogger.create[F]).map(new NoDbLoggerImpl[F](_))

  def makeUnsafe[F[_]: Async]: F[DatabaseLogger[F]] =
    Slf4jLogger.create[F].map(new NoDbLoggerImpl[F](_))

  private class NoDbLoggerImpl[F[_]: Async](
    logger: SelfAwareStructuredLogger[F]
  ) extends DatabaseLogger[F] {

    def createLogsTable(): F[Unit] = Async[F].unit

    def log[T: Encoder](logType: String, data: T): F[Unit] = logger.info(s"[$logType] $data")
    def info[T: Encoder](data: T): F[Unit] = logger.info(s"$data")
    def error[T: Encoder](data: T): F[Unit] = logger.error(s"$data")
    def warn[T: Encoder](data: T): F[Unit] = logger.warn(s"$data")
    def debug[T: Encoder](data: T): F[Unit] = logger.debug(s"$data")

    def info(message: String): F[Unit] = logger.info(message)
    def error(message: String): F[Unit] = logger.error(message)
    def warn(message: String): F[Unit] = logger.warn(message)
    def debug(message: String): F[Unit] = logger.debug(message)
  }
}
