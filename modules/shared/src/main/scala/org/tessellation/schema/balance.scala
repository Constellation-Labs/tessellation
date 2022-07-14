package org.tessellation.schema

import cats.syntax.either._
import cats.syntax.order._
import cats.syntax.semigroup._

import scala.util.Try
import scala.util.control.NoStackTrace

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.macros.newtype
import io.getquill.MappedEncoding

object balance {

  @derive(decoder, encoder, order, show)
  @newtype
  case class Amount(value: NonNegLong)

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class Balance(value: NonNegLong) {

    def plus(that: Amount): Either[BalanceArithmeticError, Balance] = {
      val sum = value |+| that.value

      if (sum >= value && sum >= that.value) {
        Balance(sum).asRight[BalanceArithmeticError]
      } else BalanceOverflow.asLeft[Balance]
    }

    def minus(that: Amount): Either[BalanceArithmeticError, Balance] =
      NonNegLong
        .from(value.value - that.value.value)
        .bimap(_ => BalanceUnderflow, Balance(_))

    def satisfiesCollateral(collateral: Amount): Boolean = value >= collateral.value
  }

  object Balance {

    val empty: Balance = Balance(NonNegLong(0L))

    implicit def toAmount(balance: Balance): Amount = Amount(balance.value)

    implicit val quillEncode: MappedEncoding[Balance, String] =
      MappedEncoding[Balance, String](_.value.value.toString())

    implicit val quillDecode: MappedEncoding[String, Balance] = MappedEncoding[String, Balance](strBalance =>
      Try(strBalance.toLong).toEither
        .flatMap(refineV[NonNegative].apply[Long](_).leftMap(new Throwable(_))) match {
        // TODO: look at quill Decode for Address
        case Left(e)  => throw e
        case Right(b) => Balance(b)
      }
    )

  }

  val normalizationFactor = 1e8.toLong

  @derive(eqv, show)
  sealed trait BalanceArithmeticError extends NoStackTrace
  case object BalanceOverflow extends BalanceArithmeticError
  case object BalanceUnderflow extends BalanceArithmeticError
}
