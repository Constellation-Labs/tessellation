package io.constellationnetwork.ext.http4s

import cats.effect.Async

import io.circe.{Encoder, Printer}
import org.http4s.headers.`Content-Type`
import org.http4s.{Entity, EntityEncoder, MediaType}

/** Entity encoders that perform JSON serialization on the blocking thread pool to prevent CPU starvation on compute threads when encoding
  * large payloads.
  *
  * The standard http4s circe encoder runs JSON serialization on the calling thread. For large payloads (70MB+ GlobalSnapshotInfo), this can
  * block compute threads for hundreds of milliseconds, causing fiber starvation.
  *
  * These encoders wrap serialization in `Async[F].blocking` to move the work to the blocking thread pool.
  */
object BlockingEntityEncoder {

  private val defaultPrinter: Printer = Printer.noSpaces.copy(dropNullValues = true)

  /** Creates an EntityEncoder that streams JSON serialization from the blocking thread pool.
    *
    * Use this for large payloads (e.g., snapshots, state) to prevent compute thread starvation.
    *
    * Example usage in routes:
    * {{{
    * import io.constellationnetwork.ext.http4s.BlockingEntityEncoder.blockingJsonEncoder
    *
    * implicit def jsonEncoders[A <: AnyRef: Encoder]: List[EntityEncoder[F, A]] =
    *   List(blockingJsonEncoder[F, A])
    * }}}
    */
  def blockingJsonEncoder[F[_]: Async, A: Encoder]: EntityEncoder[F, A] =
    blockingJsonEncoderWithPrinter[F, A](defaultPrinter)

  /** Creates an EntityEncoder with a custom Printer that serializes JSON on the blocking thread pool.
    */
  def blockingJsonEncoderWithPrinter[F[_]: Async, A: Encoder](printer: Printer): EntityEncoder[F, A] =
    EntityEncoder[F, fs2.Stream[F, Byte]]
      .contramap[A] { value =>
        fs2.Stream.evalUnChunk(
          Async[F].blocking {
            val bytes = printer.print(Encoder[A].apply(value)).getBytes("UTF-8")
            fs2.Chunk.array(bytes)
          }
        )
      }
      .withContentType(`Content-Type`(MediaType.application.json))

  /** Creates an EntityEncoder that produces the full byte array from the blocking thread pool. The bytes are then streamed as a single
    * chunk.
    *
    * This is slightly more efficient than blockingJsonEncoder for responses where you want to set Content-Length header (non-chunked
    * transfer).
    */
  def blockingJsonEncoderStrict[F[_]: Async, A: Encoder]: EntityEncoder[F, A] =
    new EntityEncoder[F, A] {
      override def toEntity(a: A): Entity[F] = {
        val bytes = fs2.Stream
          .eval(
            Async[F].blocking {
              defaultPrinter.print(Encoder[A].apply(a)).getBytes("UTF-8")
            }
          )
          .flatMap(arr => fs2.Stream.chunk(fs2.Chunk.array(arr)))

        Entity(bytes)
      }

      override def headers: org.http4s.Headers =
        org.http4s.Headers(`Content-Type`(MediaType.application.json))
    }
}
