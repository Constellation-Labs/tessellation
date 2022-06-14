package org.tessellation.ext

import scala.collection.immutable.SortedSet
import scala.reflect.ClassTag

import org.tessellation.kryo.KryoSerializer

import _root_.cats.Order
import _root_.cats.data.NonEmptySet
import _root_.cats.effect.Concurrent
import _root_.cats.syntax.option._
import io.circe.Decoder
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

  object NonEmptySetCodec {

    def decoder[A: Decoder: Ordering]: Decoder[NonEmptySet[A]] =
      Decoder.decodeNonEmptySet[A](Decoder[A], Order.fromOrdering).map { nes =>
        NonEmptySet.fromSetUnsafe(SortedSet.from(nes.toSortedSet))
      }
  }
}
