package io.constellationnetwork.schema

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.transaction.TransactionReference

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong

object priceOracle {

  @derive(decoder, encoder, order, show)
  case class PricingUpdate(currencyId: CurrencyId, fractionalValue: NonNegLong, markOverride: Boolean, lastReference: TransactionReference)
      extends DataUpdate

}
