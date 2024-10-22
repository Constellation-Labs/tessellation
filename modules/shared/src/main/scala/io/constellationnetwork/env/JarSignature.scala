package io.constellationnetwork.env

import java.security.MessageDigest

import cats.effect.Async
import cats.implicits.{catsSyntaxFlatMapOps, toFunctorOps}

import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import fs2.io.file.{Files, Path}
import fs2.{Chunk, Stream}

object JarSignature {

  def jarHash[F[_]: Async: Files]: F[Hash] = {
    val jarPath = JarSignature.getClass.getProtectionDomain.getCodeSource.getLocation.toURI
    digestOf(Files[F].readAll(Path(jarPath.getPath)))
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
