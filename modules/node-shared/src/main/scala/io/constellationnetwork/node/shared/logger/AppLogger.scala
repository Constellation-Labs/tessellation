package io.constellationnetwork.node.shared.logger

import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._

/** Main application logger interface. Combines structured logging with context scoping.
  */
trait AppLogger[F[_]] {

  // Structured logging with type-safe data
  def log[T: Encoder](logType: String, data: T): F[Unit]
  def info[T: Encoder](data: T): F[Unit]
  def error[T: Encoder](data: T): F[Unit]
  def warn[T: Encoder](data: T): F[Unit]
  def debug[T: Encoder](data: T): F[Unit]

  // Simple string logging
  def info(msg: String): F[Unit]
  def error(msg: String): F[Unit]
  def warn(msg: String): F[Unit]
  def debug(msg: String): F[Unit]

  // Context scoping - wraps an effect with additional context
  def withOrdinal[A](ordinal: SnapshotOrdinal)(fa: F[A]): F[A]
  def withOperation[A](operation: String)(fa: F[A]): F[A]
  def withPhase[A](phase: String)(fa: F[A]): F[A]
  def withCorrelationId[A](id: String)(fa: F[A]): F[A]

  // Access current context
  def currentContext: F[LogContext]
}

/** Single implementation of AppLogger that works with any LogSink. Handles context management and delegates writing to the sink.
  */
object AppLogger {

  private case class SimpleMessage(message: String)
  private object SimpleMessage {
    implicit val encoder: Encoder[SimpleMessage] = deriveEncoder
  }

  def make[F[_]: Async](sink: LogSink[F]): F[(AppLogger[F], Ref[F, LogContext])] =
    Ref.of[F, LogContext](LogContext.empty).map { ctxRef =>
      (new Impl[F](sink, ctxRef), ctxRef)
    }

  private class Impl[F[_]](
    sink: LogSink[F],
    ctxRef: Ref[F, LogContext]
  )(implicit F: Async[F])
      extends AppLogger[F] {

    private def writeLog[T: Encoder](logType: String, data: T): F[Unit] =
      for {
        ctx <- ctxRef.get
        now <- F.realTime.map(_.toMillis)
        elapsed = ctx.elapsedMs(now)
        entry = LogEntry(logType, data.asJson, ctx, elapsed)
        _ <- sink.write(entry)
      } yield ()

    // Structured logging
    def log[T: Encoder](logType: String, data: T): F[Unit] = writeLog(logType, data)
    def info[T: Encoder](data: T): F[Unit] = writeLog("INFO", data)
    def error[T: Encoder](data: T): F[Unit] = writeLog("ERROR", data)
    def warn[T: Encoder](data: T): F[Unit] = writeLog("WARN", data)
    def debug[T: Encoder](data: T): F[Unit] = writeLog("DEBUG", data)

    // String convenience
    def info(msg: String): F[Unit] = writeLog("INFO", SimpleMessage(msg))
    def error(msg: String): F[Unit] = writeLog("ERROR", SimpleMessage(msg))
    def warn(msg: String): F[Unit] = writeLog("WARN", SimpleMessage(msg))
    def debug(msg: String): F[Unit] = writeLog("DEBUG", SimpleMessage(msg))

    // Context scoping
    private def scoped[A](modify: LogContext => LogContext)(fa: F[A]): F[A] =
      for {
        original <- ctxRef.get
        _ <- ctxRef.set(modify(original))
        result <- F.guarantee(fa, ctxRef.set(original))
      } yield result

    def withOrdinal[A](ordinal: SnapshotOrdinal)(fa: F[A]): F[A] =
      scoped(_.withOrdinal(ordinal))(fa)

    def withOperation[A](operation: String)(fa: F[A]): F[A] =
      F.realTime.map(_.toMillis).flatMap { start =>
        scoped(_.withOperation(operation).withStartTime(start))(fa)
      }

    def withPhase[A](phase: String)(fa: F[A]): F[A] =
      scoped(_.withPhase(phase))(fa)

    def withCorrelationId[A](id: String)(fa: F[A]): F[A] =
      scoped(_.withCorrelationId(id))(fa)

    def currentContext: F[LogContext] = ctxRef.get
  }
}
