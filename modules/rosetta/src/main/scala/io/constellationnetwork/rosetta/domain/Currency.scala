package io.constellationnetwork.rosetta.domain

import io.constellationnetwork.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration

import derevo.cats.{eqv, show}
import derevo.circe.magnolia._
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.generic.Equal
import io.circe.refined._
import io.estatico.newtype.macros.newtype

object currency {
  @derive(eqv, customizableDecoder, customizableEncoder, show)
  case class Currency(
    symbol: CurrencySymbol,
    decimals: CurrencyDecimal
  )

  type CurrencySymbolRefined = String Refined Equal["DAG"]

  @derive(eqv, decoder, encoder, show)
  @newtype
  case class CurrencySymbol(value: CurrencySymbolRefined)

  type CurrencyDecimalRefined = Long Refined Equal[8L]

  @derive(eqv, decoder, encoder, show)
  @newtype
  case class CurrencyDecimal(value: CurrencyDecimalRefined)

  val DAG: Currency = Currency(CurrencySymbol("DAG"), CurrencyDecimal(8L))
}
