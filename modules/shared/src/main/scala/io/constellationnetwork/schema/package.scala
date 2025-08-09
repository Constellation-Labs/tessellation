package io.constellationnetwork

import cats._
import cats.implicits.toShow

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.{Duration, FiniteDuration}

import io.constellationnetwork.ext.refined._

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.numeric.{NonNegative, Positive}
import eu.timepit.refined.types.numeric._
import io.circe._

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

  implicit val posLongKeyEncoder: KeyEncoder[PosLong] =
    keyEncoderOf[Long, Positive]

  implicit val posLongKeyDecoder: KeyDecoder[PosLong] =
    keyDecoderOf[Long, Positive]

  implicit val posLongDecoder: Decoder[PosLong] =
    decoderOf[Long, Positive]

  implicit val posIntDecoder: Decoder[PosInt] =
    decoderOf[Int, Positive]

  implicit val posIntEncoder: Encoder[PosInt] =
    encoderOf[Int, Positive]

  implicit val nonNegLongEncoder: Encoder[NonNegLong] =
    encoderOf[Long, NonNegative]

  implicit val nonNegLongDecoder: Decoder[NonNegLong] =
    decoderOf[Long, NonNegative]

  implicit val nonNegLongKeyEncoder: KeyEncoder[NonNegLong] =
    keyEncoderOf[Long, NonNegative]

  implicit val nonNegLongKeyDecoder: KeyDecoder[NonNegLong] =
    keyDecoderOf[Long, NonNegative]

  implicit val nonNegIntEncoder: Encoder[NonNegInt] =
    encoderOf[Int, NonNegative]

  implicit val nonNegIntDecoder: Decoder[NonNegInt] =
    decoderOf[Int, NonNegative]

  implicit val posDoubleEncoder: Encoder[PosDouble] =
    encoderOf[Double, Positive]

  implicit val posDoubleDecoder: Decoder[PosDouble] =
    decoderOf[Double, Positive]

  implicit def tupleKeyEncoder[A, B](implicit A: KeyEncoder[A], B: KeyEncoder[B]): KeyEncoder[(A, B)] =
    KeyEncoder.instance[(A, B)] { case (a, b) => A(a) + ":" + B(b) }

  implicit def tupleKeyDecoder[A, B](implicit A: KeyDecoder[A], B: KeyDecoder[B]): KeyDecoder[(A, B)] =
    KeyDecoder.instance[(A, B)] {
      case s"$as:$bs" => A(as).flatMap(a => B(bs).map(b => a -> b))
      case _          => None
    }

  implicit def tuple3KeyEncoder[A, B, C](implicit A: KeyEncoder[A], B: KeyEncoder[B], C: KeyEncoder[C]): KeyEncoder[(A, B, C)] =
    KeyEncoder.instance[(A, B, C)] { case (a, b, c) => A(a) + ":" + B(b) + ":" + C(c) }

  implicit def tuple3KeyDecoder[A, B, C](implicit A: KeyDecoder[A], B: KeyDecoder[B], C: KeyDecoder[C]): KeyDecoder[(A, B, C)] =
    KeyDecoder.instance[(A, B, C)] {
      case s"$as:$bs:$cs" =>
        for {
          a <- A(as)
          b <- B(bs)
          c <- C(cs)
        } yield (a, b, c)
      case _ => None
    }

  implicit val nonNegLongEq: Eq[NonNegLong] =
    eqOf[Long, NonNegative]

  implicit val nonNegLongShow: Show[NonNegLong] =
    showOf[Long, NonNegative]

  implicit val nonNegLongOrder: Order[NonNegLong] =
    orderOf[Long, NonNegative]

  implicit val errorShow: Show[Throwable] = e => s"${e.getClass.getName}{message=${e.getMessage}}"

  implicit def arrayShow[A: Show]: Show[Array[A]] = a => a.iterator.map(Show[A].show(_)).mkString("Array(", ", ", ")")

  implicit def showSortedMapAsList[K: Show, V: Show]: Show[SortedMap[K, V]] =
    Show.show(_.toList.show)

  implicit def showSortedSetAsList[T: Show]: Show[SortedSet[T]] =
    Show.show(_.toList.show)

  implicit def arrayEq[A: Eq]: Eq[Array[A]] = (xs: Array[A], ys: Array[A]) =>
    Eq[Int].eqv(xs.length, ys.length) && xs.zip(ys).forall { case (x, y) => Eq[A].eqv(x, y) }

  implicit def arrayOrder[A: Order]: Order[Array[A]] = Order.by(_.toSeq)

  implicit val encodeFiniteDuration: Encoder[FiniteDuration] =
    Encoder.encodeString.contramap(_.toString)

  implicit val decodeFiniteDuration: Decoder[FiniteDuration] =
    Decoder.decodeString.emap { str =>
      try
        Duration(str) match {
          case fd: FiniteDuration => Right(fd)
          case other              => Left(s"Expected FiniteDuration but got: $other")
        }
      catch {
        case _: Exception => Left(s"Invalid FiniteDuration: $str")
      }
    }
}
