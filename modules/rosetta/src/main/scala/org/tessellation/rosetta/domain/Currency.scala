package org.tessellation.rosetta.domain

import org.tessellation.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration

import derevo.circe.magnolia._
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.generic.Equal
import io.circe.refined._
import io.estatico.newtype.macros.newtype

object currency {
  @derive(customizableDecoder, customizableEncoder)
  case class Currency(
    symbol: CurrencySymbol,
    decimals: CurrencyDecimal
  )

  type CurrencySymbolRefined = String Refined Equal["DAG"]

  @derive(decoder, encoder)
  @newtype
  case class CurrencySymbol(value: CurrencySymbolRefined)

  type CurrencyDecimalRefined = Long Refined Equal[8L]

  @derive(decoder, encoder)
  @newtype
  case class CurrencyDecimal(value: CurrencyDecimalRefined)
}
