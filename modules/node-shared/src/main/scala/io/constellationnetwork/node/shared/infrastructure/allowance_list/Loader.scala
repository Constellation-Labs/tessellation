package io.constellationnetwork.node.shared.infrastructure.allowance_list

import cats.effect.Async
import cats.syntax.functor._

import io.constellationnetwork.domain.allowance_list.AllowanceListEntry
import io.constellationnetwork.env.env.AllowanceListPath

import fs2.data.csv._
import fs2.io.file.Files
import fs2.text
import io.estatico.newtype.ops._

trait Loader[F[_]] {
  def load(path: AllowanceListPath): F[Set[AllowanceListEntry]]
}

object Loader {

  def make[F[_]: Async]: Loader[F] =
    (path: AllowanceListPath) =>
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
}
