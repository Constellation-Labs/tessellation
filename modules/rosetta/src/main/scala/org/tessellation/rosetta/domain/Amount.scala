package org.tessellation.rosetta.domain

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.currency.{Currency, DAG}
import org.tessellation.schema.transaction.{TransactionAmount, TransactionFee}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia._
import derevo.derive
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.numeric.{Negative, NonNegative, Positive}
import eu.timepit.refined.predicates.all.Or
import io.circe.refined._
import io.estatico.newtype.macros.newtype

object amount {
  type AmountValuePredicate = Positive Or Negative
  type AmountValueRefined = Long Refined AmountValuePredicate

  @derive(eqv, decoder, encoder, show)
  @newtype
  case class AmountValue(value: AmountValueRefined) {
    def isNegative: Boolean = value < 0L
    def isPositive: Boolean = value > 0L

    def negate: AmountValue =
      AmountValue(Refined.unsafeApply[Long, AmountValuePredicate](-value.value))

    def toTransactionAmount: Option[TransactionAmount] =
      refineV[Positive](value.value).map(TransactionAmount(_)).toOption

    def toTransactionFee: Option[TransactionFee] =
      refineV[NonNegative](value.value).map(TransactionFee(_)).toOption
  }

  @derive(eqv, customizableDecoder, customizableEncoder, show)
  case class Amount(
    value: AmountValue,
    currency: Currency
  ) {
    def negate: Amount = Amount(value.negate, currency)
  }

  object Amount {
    def fromTransactionAmount(ta: TransactionAmount, currency: Currency = DAG): Amount =
      Amount(AmountValue(Refined.unsafeApply[Long, AmountValuePredicate](ta.value)), currency)
  }
}
