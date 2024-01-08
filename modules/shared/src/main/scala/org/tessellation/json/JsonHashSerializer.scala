package org.tessellation.json

import cats.effect.kernel.Sync
import cats.syntax.all._

import io.circe.{Decoder, Encoder, Printer}

trait JsonHashSerializer[F[_]] {
  def serialize[A: Encoder](content: A): F[Array[Byte]]
  def deserialize[A: Decoder](content: Array[Byte]): F[Either[Throwable, A]]
}

object JsonHashSerializer {
  def apply[F[_]: JsonHashSerializer]: JsonHashSerializer[F] = implicitly

  def forSync[F[_]: Sync]: F[JsonHashSerializer[F]] = {
    def printer = Printer(dropNullValues = true, indent = "", sortKeys = true)

    JsonBrotliBinarySerializer.forSync[F](printer).map { s =>
      new JsonHashSerializer[F] {
        def serialize[A: Encoder](content: A): F[Array[Byte]] = s.serialize[A](content)
        def deserialize[A: Decoder](content: Array[Byte]): F[Either[Throwable, A]] = s.deserialize[A](content)
      }
    }
  }
}
