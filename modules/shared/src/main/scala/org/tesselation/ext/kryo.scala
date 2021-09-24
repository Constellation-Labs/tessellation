package org.tesselation.ext

import cats.MonadThrow
import cats.syntax.either._

import scala.reflect.ClassTag

import org.tesselation.kryo.KryoSerializer

object kryo {

  implicit class RefinedSerializer[F[_]: KryoSerializer](anyRef: AnyRef) {

    def toBinary: Either[Throwable, Array[Byte]] =
      KryoSerializer[F].serialize(anyRef)
  }

  implicit class RefinedSerializerF[F[_]: MonadThrow: KryoSerializer](anyRef: AnyRef) {

    def toBinaryF: F[Array[Byte]] =
      KryoSerializer[F].serialize(anyRef).liftTo[F]
  }

  implicit class RefinedDeserializer[F[_]: KryoSerializer](bytes: Array[Byte]) {

    def fromBinary[A](implicit A: ClassTag[A]): Either[Throwable, A] =
      KryoSerializer[F].deserialize[A](bytes)
  }

  implicit class RefinedDeserializerF[F[_]: MonadThrow: KryoSerializer](bytes: Array[Byte]) {

    def fromBinaryF[A](implicit A: ClassTag[A]): F[A] =
      KryoSerializer[F].deserialize[A](bytes).liftTo[F]
  }

}
