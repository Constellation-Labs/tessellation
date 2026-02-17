package io.constellationnetwork.node.shared.infrastructure.allowance_list

import java.nio.file.NoSuchFileException

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.domain.allowance_list.AllowanceListEntry
import io.constellationnetwork.env.env.AllowanceListPath

import fs2.data.csv._
import fs2.io.file.Files
import fs2.text
import io.estatico.newtype.ops._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait Loader[F[_]] {
  def load(path: AllowanceListPath): F[Set[AllowanceListEntry]]
}

object Loader {

  def make[F[_]: Async]: Loader[F] =
    (path: AllowanceListPath) => {
      val logger = Slf4jLogger.getLoggerFromName[F]("AllowanceListLoader")
      Files
        .forAsync[F]
        .readAll(path.coerce)
        .through(text.utf8.decode)
        .through(
          decodeWithoutHeaders[AllowanceListEntry]()
        )
        .compile
        .toList
        .map(_.toSet)
        .handleErrorWith {
          case _: NoSuchFileException =>
            logger.warn(s"Allowance list file not found at ${path.coerce}, using empty set") >>
              Set.empty[AllowanceListEntry].pure[F]
          case e => Async[F].raiseError(e)
        }
    }
}
