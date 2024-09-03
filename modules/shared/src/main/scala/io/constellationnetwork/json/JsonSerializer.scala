package io.constellationnetwork.json

import cats.effect.kernel.Sync
import cats.syntax.all._

import io.circe.{Decoder, Encoder, Printer}

trait JsonSerializer[F[_]] {
  def serialize[A: Encoder](content: A): F[Array[Byte]]
  def deserialize[A: Decoder](content: Array[Byte]): F[Either[Throwable, A]]
}

object JsonSerializer {
  def apply[F[_]: JsonSerializer]: JsonSerializer[F] = implicitly

  def forSync[F[_]: Sync]: F[JsonSerializer[F]] = {
    def printer = Printer(dropNullValues = true, indent = "", sortKeys = true)

    JsonBrotliBinarySerializer.forSync[F](printer).map { s =>
      new JsonSerializer[F] {
        def serialize[A: Encoder](content: A): F[Array[Byte]] = s.serialize[A](content)
        def deserialize[A: Decoder](content: Array[Byte]): F[Either[Throwable, A]] = s.deserialize[A](content)
      }
    }
  }
}
