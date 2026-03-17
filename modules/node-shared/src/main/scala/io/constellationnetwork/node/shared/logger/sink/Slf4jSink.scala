package io.constellationnetwork.node.shared.logger.sink

import cats.effect._
import cats.syntax.functor._

import io.constellationnetwork.node.shared.logger.{LogEntry, LogSink}

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Simple sink that writes to Slf4j. No batching, no retries - just immediate logging.
  */
object Slf4jSink {

  def make[F[_]: Async]: F[LogSink[F]] =
    Slf4jLogger.create[F].map(logger => new Impl[F](logger))

  def fromLogger[F[_]](logger: SelfAwareStructuredLogger[F]): LogSink[F] =
    new Impl[F](logger)

  private class Impl[F[_]](logger: SelfAwareStructuredLogger[F]) extends LogSink[F] {

    def write(entry: LogEntry): F[Unit] = {
      val msg = entry.data.noSpaces
      entry.logType match {
        case "ERROR" => logger.error(msg)
        case "WARN"  => logger.warn(msg)
        case "DEBUG" => logger.debug(msg)
        case _       => logger.info(msg)
      }
    }
  }
}
