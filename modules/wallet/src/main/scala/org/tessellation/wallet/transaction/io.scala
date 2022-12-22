package org.tessellation.wallet.transaction

import cats.effect.Async

import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.transaction.Transaction

import _root_.io.circe.fs2._
import _root_.io.circe.syntax._
import fs2.io.file.{Files, Flags, Path}
import fs2.{Stream, text}

object io {

  def readFromJsonFile[F[_]: Files: Async](path: Path): F[Option[Signed[Transaction]]] =
    Files[F]
      .readAll(path)
      .through(text.utf8.decode)
      .through(stringStreamParser)
      .through(decoder[F, Signed[Transaction]])
      .compile
      .last

  def writeToJsonFile[F[_]: Files: Async](path: Path)(transaction: Signed[Transaction]): F[Unit] =
    Stream
      .emit(transaction)
      .covary[F]
      .map(_.asJson.noSpaces)
      .through(text.utf8.encode[F])
      .through(Files[F].writeAll(path, Flags.Write))
      .compile
      .drain
}
