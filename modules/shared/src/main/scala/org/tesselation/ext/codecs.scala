package org.tesselation.ext

import cats.effect.Concurrent
import cats.syntax.option._

import scala.reflect.ClassTag

import org.tesselation.kryo.KryoSerializer

import org.http4s.DecodeResult.{failureT, successT}
import org.http4s.{EntityDecoder, EntityEncoder, MalformedMessageBodyFailure}

object codecs {

  object BinaryCodec {
    implicit def encoder[F[_]: KryoSerializer, A <: AnyRef]: EntityEncoder[F, A] =
      EntityEncoder.byteArrayEncoder[F].contramap { anyRef =>
        KryoSerializer[F].serialize(anyRef) match {
          case Right(bytes) => bytes
          case Left(ex)     => throw ex
        }
      }

    implicit def decoder[F[_]: Concurrent: KryoSerializer, A <: AnyRef: ClassTag]: EntityDecoder[F, A] =
      EntityDecoder.byteArrayDecoder[F].flatMapR { bytes =>
        KryoSerializer[F].deserialize[A](bytes) match {
          case Right(value) => successT[F, A](value)
          case Left(ex) =>
            failureT(MalformedMessageBodyFailure("Failed to deserialize http entity body with Kryo", ex.some))
        }
      }
  }

}
