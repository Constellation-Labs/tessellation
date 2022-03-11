package org.tessellation.schema

import cats.Applicative
import cats.effect.MonadCancelThrow
import cats.kernel.Order
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.syntax.traverse._

import scala.util.Try

import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.Transaction

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.macros.newtype
import io.getquill.MappedEncoding

object balance {

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class Amount(value: NonNegLong)

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class Balance(value: NonNegLong) {

    def plus(that: Amount): Either[BalanceOutOfRange, Balance] = {
      val sum = value |+| that.value

      if (Order[NonNegLong].gteqv(sum, value) && Order[NonNegLong].gteqv(sum, that.value)) {
        Balance(sum).asRight[BalanceOutOfRange]
      } else BalanceOutOfRange("Reached Long.MaxValue when adding balances!").asLeft[Balance]
    }

    def minus(that: Amount): Either[BalanceOutOfRange, Balance] =
      NonNegLong.from(value.value - that.value.value).bimap(BalanceOutOfRange, Balance(_))
  }

  object Balance {

    val empty: Balance = Balance(NonNegLong(0L))

    implicit def toAmount(balance: Balance): Amount = Amount(balance.value)

    implicit val quillEncode: MappedEncoding[Balance, String] =
      MappedEncoding[Balance, String](_.value.value.toString())

    implicit val quillDecode: MappedEncoding[String, Balance] = MappedEncoding[String, Balance](
      strBalance =>
        Try(strBalance.toLong).toEither
          .flatMap(refineV[NonNegative].apply[Long](_).leftMap(new Throwable(_))) match {
          //TODO: look at quill Decode for Address
          case Left(e)  => throw e
          case Right(b) => Balance(b)
        }
    )

    def applyTransactions[F[_]: Applicative: MonadCancelThrow](
      transactions: Set[Transaction],
      getBalanceFn: Address => F[Balance]
    ): F[Map[Address, Balance]] = {
      val sources = transactions.groupBy(_.source)
      val destinations = transactions.groupBy(_.destination)
      val addresses = sources ++ destinations

      def applyTransactions(
        balance: Balance,
        address: Address,
        txs: Set[Transaction]
      ) =
        txs.foldLeft(balance.asRight[BalanceOutOfRange]) { (acc, tx) =>
          tx match {
            case Transaction(`address`, _, amount, fee, _, _) =>
              acc.flatMap(_.minus(amount)).flatMap(_.minus(fee))
            case Transaction(_, `address`, amount, _, _, _) => acc.flatMap(_.plus(amount))
            case _                                          => acc
          }
        }

      addresses.toList.traverse {
        case (address, txs) =>
          getBalanceFn(address).flatMap { balance =>
            applyTransactions(balance, address, txs).liftTo[F]
          }.map((address, _))
      }.map(_.toMap)
    }

  }

  case class BalanceOutOfRange(msg: String) extends Throwable(msg)
}
