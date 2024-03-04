package org.tessellation.wallet.file

import cats.effect.Async

import _root_.io.circe.fs2._
import _root_.io.circe.syntax._
import _root_.io.circe.{Decoder, Encoder}
import fs2.io.file.{Files, Flags, Path}
import fs2.{Stream, text}

object io {

  def readFromJsonFile[F[_]: Async, A: Decoder](path: Path): F[Option[A]] =
    Files
      .forAsync[F]
      .readAll(path)
      .through(text.utf8.decode)
      .through(stringStreamParser)
      .through(decoder[F, A])
      .compile
      .last

  def writeToJsonFile[F[_]: Async, A: Encoder](path: Path)(a: A): F[Unit] =
    Stream
      .emit(a)
      .covary[F]
      .map(_.asJson.noSpaces)
      .through(text.utf8.encode[F])
      .through(Files.forAsync[F].writeAll(path, Flags.Write))
      .compile
      .drain
}
