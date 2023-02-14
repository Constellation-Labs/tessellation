package org.tessellation.rosetta.domain

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.tessellation.rosetta.domain.currency.Currency

import derevo.circe.magnolia._
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Not
import eu.timepit.refined.generic.Equal
import io.circe.refined._
import io.estatico.newtype.macros.newtype

object amount {
  @derive(customizableDecoder, customizableEncoder)
  case class Amount(
    value: AmountValue,
    currency: Currency
  )

  type AmountValueRefined = Long Refined Not[Equal[0L]]

  @derive(decoder, encoder)
  @newtype
  case class AmountValue(value: AmountValueRefined) {
    def isNegative: Boolean = value < 0L
  }
}
