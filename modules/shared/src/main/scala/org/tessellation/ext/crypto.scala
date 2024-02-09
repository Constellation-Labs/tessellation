package org.tessellation.ext

import java.security.KeyPair

import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}

import _root_.cats.data.NonEmptyList
import _root_.cats.effect.kernel.Async
import _root_.cats.syntax.all._
import io.circe.Encoder

object crypto {
  implicit class RefinedHasher[F[_]: Hasher, A: Encoder](content: A) {

    def hash: F[Hash] = Hasher[F].hash(content)
  }

  implicit class RefinedSignedF[F[_]: Async: Hasher: SecurityProvider, A: Encoder](data: A) {
    def sign(keyPair: KeyPair): F[Signed[A]] = Signed.forAsyncHasher[F, A](data, keyPair)

    def sign(keyPairs: NonEmptyList[KeyPair]): F[Signed[A]] =
      keyPairs.tail.foldLeft(sign(keyPairs.head)) { (acc, keyPair) =>
        acc.flatMap(_.signAlsoWith(keyPair))
      }
  }
}
