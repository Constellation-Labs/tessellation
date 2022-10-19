package org.tessellation.schema

import cats.syntax.either._
import cats.syntax.semigroup._

import scala.util.Try
import scala.util.control.NoStackTrace

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.macros.newtype
import io.getquill.MappedEncoding
import monocle.Iso

object balance {

  @derive(eqv, decoder, encoder, order, show)
  @newtype
  case class Amount(value: NonNegLong) {

    def plus(that: Amount): Either[BalanceArithmeticError, Amount] = {
      val sum = value |+| that.value

      if (sum >= value && sum >= that.value) {
        Amount(sum).asRight[BalanceArithmeticError]
      } else AmountOverflow.asLeft[Amount]
    }

    def minus(that: Amount): Either[BalanceArithmeticError, Amount] =
      NonNegLong
        .from(value.value - that.value.value)
        .bimap(_ => AmountUnderflow, Amount(_))
  }

  object Amount {
    val empty: Amount = Amount(NonNegLong(0L))
  }

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class Balance(value: NonNegLong) {

    def plus(that: Amount): Either[BalanceArithmeticError, Balance] =
      Balance.toAmount(this).plus(that).map(_.value).map(Balance(_))

    def minus(that: Amount): Either[BalanceArithmeticError, Balance] =
      Balance.toAmount(this).minus(that).map(_.value).map(Balance(_))

    def satisfiesCollateral(collateral: Amount): Boolean = value >= collateral.value
  }

  object Balance {

    val empty: Balance = Balance(NonNegLong(0L))

    val _amount: Iso[Balance, Amount] =
      Iso[Balance, Amount](balance => Amount(balance.value))(amount => Balance(amount.value))

    implicit val toAmount: Balance => Amount = _amount.get

    implicit val fromAmount: Amount => Balance = _amount.reverseGet

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

  @derive(eqv, show)
  sealed trait BalanceArithmeticError extends NoStackTrace
  case object AmountOverflow extends BalanceArithmeticError
  case object AmountUnderflow extends BalanceArithmeticError
}
