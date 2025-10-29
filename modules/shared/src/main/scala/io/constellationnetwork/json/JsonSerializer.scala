package io.constellationnetwork.json

import cats.effect.Sync
import cats.syntax.all._

import io.circe.{Decoder, Encoder, Printer}

trait JsonSerializer[F[_]] {
  def serialize[A: Encoder](content: A): F[Array[Byte]]
  def deserialize[A: Decoder](content: Array[Byte]): F[Either[Throwable, A]]
}

object JsonSerializer {
  def apply[F[_]](implicit ev: JsonSerializer[F]): JsonSerializer[F] = ev

  def forSync[F[_]: Sync]: F[JsonSerializer[F]] = {
    val printer = Printer(dropNullValues = true, indent = "", sortKeys = true)
    JsonBrotliBinarySerializer.forSync[F](printer).map { brotli =>
      new JsonSerializer[F] {
        override def serialize[A: Encoder](content: A): F[Array[Byte]] =
          brotli.serialize(content)

        override def deserialize[A: Decoder](content: Array[Byte]): F[Either[Throwable, A]] =
          brotli.deserialize(content)
      }
    }
  }
}
