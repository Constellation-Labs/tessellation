package org.tessellation.schema.ext

import java.security.KeyPair

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.security.{Hashable, SecurityProvider}

import _root_.cats.MonadThrow
import _root_.cats.data.NonEmptyList
import _root_.cats.effect.kernel.Async
import _root_.cats.syntax.either._
import _root_.cats.syntax.flatMap._

object crypto {
  implicit class RefinedHashable[F[_]: KryoSerializer](anyRef: AnyRef) {

    def hash: Either[Throwable, Hash] = Hashable.forKryo[F].hash(anyRef)
  }

  implicit class RefinedHashableF[F[_]: MonadThrow: KryoSerializer](anyRef: AnyRef) {

    def hashF: F[Hash] = Hashable.forKryo[F].hash(anyRef).liftTo[F]
  }

  implicit class RefinedSignedF[F[_]: Async: KryoSerializer: SecurityProvider, A <: AnyRef](data: A) {

    def sign(keyPair: KeyPair): F[Signed[A]] = Signed.forAsyncKryo[F, A](data, keyPair)

    def sign(keyPairs: NonEmptyList[KeyPair]): F[Signed[A]] =
      keyPairs.tail.foldLeft(sign(keyPairs.head)) { (acc, keyPair) =>
        acc.flatMap(_.signAlsoWith(keyPair))
      }
  }
}
