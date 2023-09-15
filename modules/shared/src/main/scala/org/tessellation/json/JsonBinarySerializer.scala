package org.tessellation.json

import cats.effect.kernel.Async
import cats.syntax.functor._

import de.lhns.fs2.compress.{GzipCompressor, GzipDecompressor}
import fs2.Stream
import fs2.compression.Compression
import io.circe.jawn.JawnParser
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

object JsonBinarySerializer {
  def serialize[F[_]: Async: Compression, A: Encoder](content: A): F[Array[Byte]] =
    Stream
      .fromIterator(content.asJson.noSpaces.getBytes("UTF-8").iterator, 8)
      .through(GzipCompressor.make[F]().compress)
      .compile
      .toList
      .map(_.toArray)

  def deserialize[F[_]: Async: Compression, A: Decoder](content: Array[Byte]): F[Either[Throwable, A]] =
    Stream
      .fromIterator(content.iterator, 8)
      .through(GzipDecompressor.make[F]().decompress)
      .compile
      .toList
      .map(_.toArray)
      .map(JawnParser(false).decodeByteArray[A])
}
