package io.constellationnetwork.env

import java.nio.file.NoSuchFileException
import java.security.MessageDigest

import cats.effect.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import fs2.io.file.{Files, Path}
import fs2.{Chunk, Stream}

object JarSignature {

  case class JarNotFound(path: Path) extends NoStackTrace {
    override def getMessage: String = s"JAR file not found at $path - cannot compute signature"
  }

  def jarHash[F[_]: Async: Files]: F[Hash] = {
    val jarPath = Path(JarSignature.getClass.getProtectionDomain.getCodeSource.getLocation.toURI.getPath)
    digestOf(Files[F].readAll(jarPath)).adaptError {
      case _: NoSuchFileException => JarNotFound(jarPath)
    }
  }

  def digestOf[F[_]: Async](bytes: Stream[F, Byte]): F[Hash] = {

    val streamChunkSizeBytes = 524288

    digestInstance >>= { digest =>
      bytes
        .chunkN(streamChunkSizeBytes)
        .evalMap(updateDigest(digest, _))
        .compile
        .drain >> toHash(digest)
    }
  }

  private def digestInstance[F[_]: Async] =
    Async[F].delay(MessageDigest.getInstance("SHA-256"))

  private def updateDigest[F[_]: Async](digest: MessageDigest, chunk: Chunk[Byte]) =
    Async[F].delay(digest.update(chunk.toArray))

  private def toHash[F[_]: Async](digest: MessageDigest) =
    Async[F].delay(digest.digest()).map(Hex.fromBytes(_)).map(_.value).map(Hash.apply)

}
