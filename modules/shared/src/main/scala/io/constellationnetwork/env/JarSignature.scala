package io.constellationnetwork.env

import java.security.MessageDigest

import cats.effect.IO

import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import fs2.io.file.{Files, Path}

object JarSignature {

  val jarHash: IO[Hash] = {

    val chunkSize = 524288
    val jarPath = JarSignature.getClass.getProtectionDomain.getCodeSource.getLocation.toURI.getPath

    IO(MessageDigest.getInstance("SHA-256")).flatMap { digest =>
      Files[IO]
        .readAll(Path(jarPath))
        .chunkN(chunkSize) // Process file in chunks
        .evalMap { chunk =>
          IO(digest.update(chunk.toArray))
        }
        .compile
        .drain >> IO(Hash(Hex.fromBytes(digest.digest()).value))
    }
  }

}
