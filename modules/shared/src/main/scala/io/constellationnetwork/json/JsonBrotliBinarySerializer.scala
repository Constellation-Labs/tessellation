package io.constellationnetwork.json

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.charset.StandardCharsets

import cats.effect.Async
import cats.syntax.all._

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.Decoder.{decompress => brotliDecompress}
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream
import com.aayushatharva.brotli4j.encoder.Encoder.Parameters
import io.circe.jawn.JawnParser
import io.circe.{Decoder, Encoder, Printer}

trait JsonBrotliBinarySerializer[F[_]] {
  def serialize[A: Encoder](content: A): F[Array[Byte]]
  def deserialize[A: Decoder](content: Array[Byte]): F[Either[Throwable, A]]
}

object JsonBrotliBinarySerializer {
  // Historical protocol encoder: v35 Currency certified-lineage V1 reconstructs binary
  // content with this exact sorted/drop-null JSON + Brotli quality-2 pipeline. Its dependency,
  // parameters and output must remain available for V1 replay. Encoder evolution belongs in a
  // versioned implementation; it must not silently alter this one.
  private val compressionLevel = 2
  private val parser = JawnParser(allowDuplicateKeys = false)
  private val UTF8 = StandardCharsets.UTF_8

  private class OutputStreamAppendable(os: OutputStream) extends Appendable {
    def append(csq: CharSequence): Appendable = { os.write(csq.toString.getBytes(UTF8)); this }
    def append(csq: CharSequence, start: Int, end: Int): Appendable = {
      os.write(csq.subSequence(start, end).toString.getBytes(UTF8)); this
    }
    def append(c: Char): Appendable = {
      if (c < 128) os.write(c.toInt) else os.write(c.toString.getBytes(UTF8))
      this
    }
  }

  private def streamPrintAndCompress[A](content: A, printer: Printer, params: Parameters)(implicit enc: Encoder[A]): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val brotli = new BrotliOutputStream(baos, params)
    val appendable = new OutputStreamAppendable(brotli)

    try
      enc match {
        case sce: StreamingCollectionEncoder[A] @unchecked =>
          sce.streamEncode(content, printer, appendable)
        case _ =>
          printer.unsafePrintToAppendable(enc(content), appendable)
      }
    finally
      brotli.close()

    baos.toByteArray
  }

  def apply[F[_]: JsonBrotliBinarySerializer]: JsonBrotliBinarySerializer[F] = implicitly

  def forAsync[F[_]: Async](printer: Printer): F[JsonBrotliBinarySerializer[F]] =
    Async[F].delay(Brotli4jLoader.ensureAvailability()).map { _ =>
      new JsonBrotliBinarySerializer[F] {
        private val params = new Parameters().setQuality(compressionLevel)

        def serialize[A: Encoder](content: A): F[Array[Byte]] =
          Async[F].blocking {
            streamPrintAndCompress(content, printer, params)
          } <* Async[F].cede

        def deserialize[A: Decoder](content: Array[Byte]): F[Either[Throwable, A]] =
          Async[F].blocking {
            val decompressed = brotliDecompress(content).getDecompressedData
            parser
              .parseByteBuffer(java.nio.ByteBuffer.wrap(decompressed))
              .flatMap[Throwable, A](_.as[A])
          } <* Async[F].cede
      }
    }
}
