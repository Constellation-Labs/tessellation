package io.constellationnetwork.node.shared.infrastructure.seedlist

import cats.effect.Async
import cats.syntax.functor._

import io.constellationnetwork.env.env.SeedListPath
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry._

import fs2.data.csv._
import fs2.io.file.Files
import fs2.text
import io.estatico.newtype.ops._

trait Loader[F[_]] {
  def load(path: SeedListPath): F[Set[SeedlistEntry]]
}

object Loader {

  def make[F[_]: Async]: Loader[F] =
    (path: SeedListPath) =>
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
}
