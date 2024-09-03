package io.constellationnetwork.ext

import _root_.cats._
import _root_.cats.syntax.bifunctor._
import _root_.cats.syntax.either._
import _root_.cats.syntax.foldable._
import _root_.cats.syntax.order._
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import io.circe._

package object refined {

  implicit class NonNegLongOps(a: NonNegLong) {

    import scala.math.Integral.Implicits._

    def +(b: NonNegLong): Either[ArithmeticException, NonNegLong] =
      Either.catchOnly[ArithmeticException](NonNegLong.unsafeFrom(Math.addExact(a, b)))

    def -(b: NonNegLong): Either[ArithmeticException, NonNegLong] =
      Either.cond(a >= b, NonNegLong.unsafeFrom(a.value - b.value), new ArithmeticException("cannot subtract greater number from smaller"))

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

  implicit class FoldableNonNegLongOps[G[_]: Foldable](xs: G[NonNegLong]) {
    def sumAll: Either[ArithmeticException, NonNegLong] =
      xs.foldM(NonNegLong.MinValue) { (acc, weight) =>
        acc + weight
      }
  }

  // For exemplary validator definition look into DAGAddressRefined object

  def decoderOf[T, P](implicit v: Validate[T, P], d: Decoder[T]): Decoder[T Refined P] =
    d.emap(refineV[P].apply[T](_))

  def encoderOf[T, P](implicit e: Encoder[T]): Encoder[T Refined P] =
    e.contramap(_.value)

  def keyDecoderOf[T, P](implicit v: Validate[T, P], d: KeyDecoder[T]): KeyDecoder[T Refined P] =
    KeyDecoder.instance(s => d(s).flatMap(t => refineV[P](t).toOption))

  def keyEncoderOf[T, P](implicit e: KeyEncoder[T]): KeyEncoder[T Refined P] =
    e.contramap(_.value)

  def eqOf[T, P](implicit eqT: Eq[T]): Eq[T Refined P] =
    Eq.instance((a, b) => eqT.eqv(a.value, b.value))

  def showOf[T, P](implicit showT: Show[T]): Show[T Refined P] =
    Show.show(r => showT.show(r.value))

  def orderOf[T, P](implicit orderT: Order[T]): Order[T Refined P] =
    (x: T Refined P, y: T Refined P) => x.value.compare(y.value)

}
