package org.tessellation.sdk.infrastructure.seedlist

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.seedlist.types.SeedlistCSVEntry

import fs2.data.csv.lowlevel._
import fs2.io.file.{Files, Path}
import fs2.text
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait Loader[F[_]] {
  def load(path: Path): F[Set[PeerId]]
}

object Loader {

  def make[F[_]: Async]: Loader[F] = {
    val logger = Slf4jLogger.getLoggerFromClass[F](Loader.getClass)

    (path: Path) =>
      Files[F]
        .readAll(path)
        .through(text.utf8.decode)
        .through(rows())
        .through(attemptDecode[F, SeedlistCSVEntry])
        .evalMapFilter {
          case Left(err)    => logger.warn(s"Invalid seedlist entry, line ${err.line.show}").as(none[SeedlistCSVEntry])
          case Right(entry) => entry.some.pure[F]
        }
        .map(_.peerId)
        .compile
        .toList
        .map(_.toSet)
  }
}
