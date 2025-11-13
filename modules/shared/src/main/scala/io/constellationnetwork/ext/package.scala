package io.constellationnetwork.ext

import scala.reflect.ClassTag

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer

import _root_.cats.MonadThrow
import _root_.cats.syntax.all._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or
import io.circe.{Decoder, Encoder}

object json {

  implicit class RefinedJsonSerializerF[F[_]: MonadThrow: JsonSerializer, A: Encoder](content: A) {

    def toBinaryF: F[Array[Byte]] =
      JsonSerializer[F].serialize(content)
  }

  implicit class RefinedJsonDeserializerF[F[_]: MonadThrow: JsonSerializer](bytes: Array[Byte]) {

    def fromBinaryF[A: Decoder]: F[A] =
      JsonSerializer[F].deserialize[A](bytes).flatMap {
        case Right(value) => MonadThrow[F].pure(value)
        case Left(error)  => MonadThrow[F].raiseError(error)
      }
  }
}
