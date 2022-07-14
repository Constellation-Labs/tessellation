package org.tessellation

import cats._

import org.tessellation.ext.refined._

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.numeric.{NonNegative, Positive}
import eu.timepit.refined.types.numeric._
import io.circe.{Decoder, Encoder}

package object schema extends OrphanInstances

// instances for types we don't control
trait OrphanInstances {
  implicit val hostDecoder: Decoder[Host] =
    Decoder[String].emap(s => Host.fromString(s).toRight("Invalid host"))

  implicit val hostEncoder: Encoder[Host] =
    Encoder[String].contramap(_.toString)

  implicit val portDecoder: Decoder[Port] =
    Decoder[Int].emap(p => Port.fromInt(p).toRight("Invalid port"))

  implicit val portEncoder: Encoder[Port] =
    Encoder[Int].contramap(_.value)

  implicit val posLongEq: Eq[PosLong] =
    eqOf[Long, Positive]

  implicit val posLongShow: Show[PosLong] =
    showOf[Long, Positive]

  implicit val posLongOrder: Order[PosLong] =
    orderOf[Long, Positive]

  implicit val posLongEncoder: Encoder[PosLong] =
    encoderOf[Long, Positive]

  implicit val posLongDecoder: Decoder[PosLong] =
    decoderOf[Long, Positive]

  implicit val nonNegLongEncoder: Encoder[NonNegLong] =
    encoderOf[Long, NonNegative]

  implicit val nonNegLongDecoder: Decoder[NonNegLong] =
    decoderOf[Long, NonNegative]

  implicit val nonNegLongEq: Eq[NonNegLong] =
    eqOf[Long, NonNegative]

  implicit val nonNegLongShow: Show[NonNegLong] =
    showOf[Long, NonNegative]

  implicit val nonNegLongOrder: Order[NonNegLong] =
    orderOf[Long, NonNegative]
}
