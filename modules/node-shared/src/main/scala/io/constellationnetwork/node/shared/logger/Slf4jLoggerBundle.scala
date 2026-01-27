package io.constellationnetwork.node.shared.logger

import cats.effect._
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.node.shared.logger.sink.{Slf4jConsensusLogger, Slf4jSink}

import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Creates a logger bundle that writes everything to Slf4j. Use this when ClickHouse is not configured.
  */
object Slf4jLoggerBundle {

  def make[F[_]: Async]: Resource[F, LoggerBundle[F]] =
    Resource.eval(makeF[F])

  /** Alias for makeF - creates bundle outside of Resource context */
  def makeUnsafe[F[_]: Async]: F[LoggerBundle[F]] = makeF[F]

  def makeF[F[_]: Async]: F[LoggerBundle[F]] =
    for {
      slf4j <- Slf4jLogger.create[F]
      sink = Slf4jSink.fromLogger(slf4j)
      (appLogger, ctxRef) <- AppLogger.make[F](sink)
      consensusLogger = Slf4jConsensusLogger.make[F](slf4j, ctxRef)
    } yield LoggerBundle(appLogger, consensusLogger)
}
