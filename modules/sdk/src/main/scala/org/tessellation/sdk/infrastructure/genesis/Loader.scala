package org.tessellation.sdk.infrastructure.genesis

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.functor._

import org.tessellation.sdk.domain.genesis.Loader
import org.tessellation.sdk.domain.genesis.types.GenesisCSVAccount

import fs2.data.csv._
import fs2.io.file.{Files, Path}
import fs2.text

object Loader {

  def make[F[_]: Async]: Loader[F] =
    (path: Path) =>
      Files[F]
        .readAll(path)
        .through(text.utf8.decode)
        .through(
          decodeWithoutHeaders[GenesisCSVAccount]()
        )
        .map(_.toGenesisAccount)
        .map(_.bimap(e => new RuntimeException(e), identity))
        .rethrow
        .compile
        .toList
        .map(_.toSet)
}
