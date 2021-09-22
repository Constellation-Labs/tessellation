package org.tesselation.ext

import cats.MonadThrow
import cats.syntax.either._

import org.tesselation.crypto.hash.Hash
import org.tesselation.crypto.{Hashable, Signed}
import org.tesselation.kryo.KryoSerializer

object crypto {
  implicit class RefinedHashable[F[_]: KryoSerializer](anyRef: AnyRef) {

    def hash: Either[Throwable, Hash] = Hashable.forKryo[F].hash(anyRef)
  }

  implicit class RefinedHashableF[F[_]: MonadThrow: KryoSerializer](anyRef: AnyRef) {

    def hashF: F[Hash] = Hashable.forKryo[F].hash(anyRef).liftTo[F]
  }

  implicit class RefinedSigned[F[_]: KryoSerializer, A <: AnyRef](data: A) {

    def sign(): Either[Throwable, Signed[A]] = Signed.forKryo[F, A](data)
  }

  implicit class RefinedSignedF[F[_]: KryoSerializer, A <: AnyRef](data: A) {

    def signF[M[_]: MonadThrow](): M[Signed[A]] = Signed.forKryo[F, A](data).liftTo[M]
  }
}
