package org.tessellation.ext

import _root_.cats.syntax.bifunctor._
import _root_.cats.syntax.either._
import _root_.cats.syntax.order._
import _root_.cats.{Eq, Order, Show}
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import io.circe.{Decoder, Encoder}

package object refined {

  implicit class NonNegLongOps(a: NonNegLong) {

    import scala.math.Integral.Implicits._

    def +(b: NonNegLong): Either[ArithmeticException, NonNegLong] =
      Either.catchOnly[ArithmeticException](NonNegLong.unsafeFrom(Math.addExact(a, b)))

    def -(b: NonNegLong): Either[ArithmeticException, NonNegLong] =
      Either.catchOnly[ArithmeticException](NonNegLong.unsafeFrom(Math.subtractExact(a, b)))

    def *(b: NonNegLong): Either[ArithmeticException, NonNegLong] =
      Either.catchOnly[ArithmeticException](NonNegLong.unsafeFrom(Math.multiplyExact(a, b)))

    def /(b: PosLong): NonNegLong =
      NonNegLong.unsafeFrom(a.value / b.value)

    def /(b: NonNegLong): Either[ArithmeticException, NonNegLong] =
      Either.cond(b =!= 0L, NonNegLong.unsafeFrom(a.value / b.value), new ArithmeticException("division by zero"))

    def /%(b: PosLong): (NonNegLong, NonNegLong) =
      (a.value /% b.value).bimap(NonNegLong.unsafeFrom, NonNegLong.unsafeFrom)

    def /%(b: NonNegLong): Either[ArithmeticException, (NonNegLong, NonNegLong)] =
      Either.cond(
        b =!= 0L,
        (a.value /% b.value).bimap(NonNegLong.unsafeFrom, NonNegLong.unsafeFrom),
        new ArithmeticException("division by zero")
      )
  }

  // For exemplary validator definition look into DAGAddressRefined object

  def decoderOf[T, P](implicit v: Validate[T, P], d: Decoder[T]): Decoder[T Refined P] =
    d.emap(refineV[P].apply[T](_))

  def encoderOf[T, P](implicit e: Encoder[T]): Encoder[T Refined P] =
    e.contramap(_.value)

  def eqOf[T, P](implicit eqT: Eq[T]): Eq[T Refined P] =
    Eq.instance((a, b) => eqT.eqv(a.value, b.value))

  def showOf[T, P](implicit showT: Show[T]): Show[T Refined P] =
    Show.show(r => showT.show(r.value))

  def orderOf[T, P](implicit orderT: Order[T]): Order[T Refined P] =
    (x: T Refined P, y: T Refined P) => x.value.compare(y.value)

}
