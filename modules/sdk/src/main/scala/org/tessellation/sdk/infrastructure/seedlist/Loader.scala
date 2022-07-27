package org.tessellation.sdk.infrastructure.seedlist

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.seedlist.types.SeedlistCSVEntry

import fs2.data.csv._
import fs2.io.file.{Files, Path}
import fs2.text

trait Loader[F[_]] {
  def load(path: Path): F[Set[PeerId]]
}

object Loader {

  def make[F[_]: Async]: Loader[F] =
    (path: Path) =>
      Files[F]
        .readAll(path)
        .through(text.utf8.decode)
        .through(
          decodeWithoutHeaders[SeedlistCSVEntry]()
        )
        .map(_.toPeerId)
        .compile
        .toList
        .map(_.toSet)
}
