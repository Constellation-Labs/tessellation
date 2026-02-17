package io.constellationnetwork.node.shared.infrastructure.seedlist

import java.nio.file.NoSuchFileException

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.env.env.SeedListPath

import fs2.data.csv._
import fs2.io.file.Files
import fs2.text
import io.estatico.newtype.ops._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait Loader[F[_]] {
  def load(path: SeedListPath): F[Set[SeedlistEntry]]
}

object Loader {

  def make[F[_]: Async]: Loader[F] =
    (path: SeedListPath) => {
      val logger = Slf4jLogger.getLoggerFromName[F]("SeedlistLoader")
      Files
        .forAsync[F]
        .readAll(path.coerce)
        .through(text.utf8.decode)
        .through(
          decodeWithoutHeaders[SeedlistEntry]()
        )
        .compile
        .toList
        .map(_.toSet)
        .handleErrorWith {
          case _: NoSuchFileException =>
            logger.warn(s"Seedlist file not found at ${path.coerce}, using empty set") >>
              Set.empty[SeedlistEntry].pure[F]
          case e => Async[F].raiseError(e)
        }
    }
}
