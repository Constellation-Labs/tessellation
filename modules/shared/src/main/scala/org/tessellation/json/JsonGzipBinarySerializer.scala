package org.tessellation.json

import cats.effect.Async
import cats.syntax.functor._

import de.lhns.fs2.compress.{GzipCompressor, GzipDecompressor}
import fs2.Stream
import fs2.compression.Compression
import io.circe.jawn.JawnParser
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Error}

trait JsonGzipBinarySerializer[F[_]] {
  def serialize[A: Encoder](content: A): F[Array[Byte]]
  def deserialize[A: Decoder](content: Array[Byte]): F[Either[Error, A]]
}

object JsonGzipBinarySerializer {
  def make[F[_]: Async: Compression](): JsonGzipBinarySerializer[F] =
    new JsonGzipBinarySerializer[F] {
      private val compressor = GzipCompressor.make[F]()
      private val decompressor = GzipDecompressor.make[F]()

      def serialize[A: Encoder](content: A): F[Array[Byte]] =
        Stream
          .emits(content.asJson.noSpaces.getBytes("UTF-8"))
          .through(compressor.compress)
          .compile
          .toList
          .map(_.toArray)

      def deserialize[A: Decoder](content: Array[Byte]): F[Either[Error, A]] =
        Stream
          .emits(content)
          .through(decompressor.decompress)
          .compile
          .toList
          .map(_.toArray)
          .map(JawnParser(false).decodeByteArray[A])
    }
}
