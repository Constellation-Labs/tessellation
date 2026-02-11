package io.constellationnetwork.wallet.file

import java.nio.file.NoSuchFileException

import cats.effect.Async
import cats.syntax.all._

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
      .handleErrorWith {
        case _: NoSuchFileException => none[A].pure[F]
        case e                      => Async[F].raiseError(e)
      }

  def writeToJsonFile[F[_]: Async, A: Encoder](path: Path)(a: A): F[Unit] = {
    // Ensure parent directory exists before writing
    val ensureParent = Option(path.parent).traverse_(Files.forAsync[F].createDirectories)

    ensureParent >>
      Stream
        .emit(a)
        .covary[F]
        .map(_.asJson.noSpaces)
        .through(text.utf8.encode[F])
        .through(Files.forAsync[F].writeAll(path, Flags.Write))
        .compile
        .drain
  }
}
