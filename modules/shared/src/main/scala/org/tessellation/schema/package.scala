package org.tessellation

import cats._
import cats.syntax.semigroup._

import org.tessellation.ext.refined._

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.numeric.{NonNegative, Positive}
import eu.timepit.refined.types.numeric.{NonNegBigInt, PosBigInt, PosLong}
import io.circe.{Decoder, Encoder}

package object schema extends OrphanInstances

// instances for types we don't control
trait OrphanInstances {
  implicit val hostDecoder: Decoder[Host] =
    Decoder[String].emap(s => Host.fromString(s).toRight("Invalid host"))

  implicit val hostEncoder: Encoder[Host] =
    Encoder[String].contramap(_.toString)

  implicit val portDecoder: Decoder[Port] = {
    Decoder[Int].emap(p => Port.fromInt(p).toRight("Invalid port"))
  }

  implicit val portEncoder: Encoder[Port] =
    Encoder[Int].contramap(_.value)

  implicit val nonNegBigIntDecoder: Decoder[NonNegBigInt] =
    decoderOf[BigInt, NonNegative]

  implicit val nonNegBigIntEncoder: Encoder[NonNegBigInt] =
    encoderOf[BigInt, NonNegative]

  implicit val nonNegBigIntEq: Eq[NonNegBigInt] =
    eqOf[BigInt, NonNegative]

  implicit val nonNegBigIntShow: Show[NonNegBigInt] =
    showOf[BigInt, NonNegative]

  implicit val nonNegBigIntSemigroup: Semigroup[NonNegBigInt] =
    (x: NonNegBigInt, y: NonNegBigInt) => NonNegBigInt.unsafeFrom(x.value |+| y.value)

  implicit val posBigIntDecoder: Decoder[PosBigInt] =
    decoderOf[BigInt, Positive]

  implicit val posBigIntEncoder: Encoder[PosBigInt] =
    encoderOf[BigInt, Positive]

  implicit val posBigIntEq: Eq[PosBigInt] =
    eqOf[BigInt, Positive]

  implicit val posBigIntShow: Show[PosBigInt] =
    showOf[BigInt, Positive]

  implicit val posLongShow: Show[PosLong] =
    showOf[Long, Positive]

  implicit val posLongOrder: Order[PosLong] =
    orderOf[Long, Positive]
}
