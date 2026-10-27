package io.constellationnetwork.json

import java.io.ByteArrayInputStream

import cats.effect.kernel.Sync
import cats.syntax.applicativeError._
import cats.syntax.functor._

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.encoder.Encoder.Parameters
import com.aayushatharva.brotli4j.encoder.{Encoder => BrotliEncoder}
import io.circe.jawn.JawnParser
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Printer}
import org.brotli.dec.BrotliInputStream

trait JsonBrotliBinarySerializer[F[_]] {
  def serialize[A: Encoder](content: A): F[Array[Byte]]
  def deserialize[A: Decoder](content: Array[Byte]): F[Either[Throwable, A]]
}

object JsonBrotliBinarySerializer {
  private val compressionLevel = 2

  def apply[F[_]: JsonBrotliBinarySerializer]: JsonBrotliBinarySerializer[F] = implicitly

  def forSync[F[_]: Sync]: F[JsonBrotliBinarySerializer[F]] = {
    def printer = Printer(dropNullValues = false, indent = "")

    forSync[F](printer)
  }

  def forSync[F[_]: Sync](printer: Printer): F[JsonBrotliBinarySerializer[F]] =
    Sync[F].delay(Brotli4jLoader.ensureAvailability()).map { _ =>
      new JsonBrotliBinarySerializer[F] {

        def serialize[A: Encoder](content: A): F[Array[Byte]] = {
          val params = new Parameters().setQuality(compressionLevel)
          Sync[F].delay(BrotliEncoder.compress(content.asJson.printWith(printer).getBytes("UTF-8"), params))
        }

        def deserialize[A: Decoder](content: Array[Byte]): F[Either[Throwable, A]] =
          Sync[F].delay {
            // Decode untrusted streams in Java; retain the native encoder for exact hash/signature compatibility.
            val stream = new BrotliInputStream(new ByteArrayInputStream(content))
            val decompressed =
              try stream.readAllBytes()
              finally stream.close()
            JawnParser(false).decodeByteArray[A](decompressed)
          }.attempt
            .map(_.flatten)
      }
    }
}
