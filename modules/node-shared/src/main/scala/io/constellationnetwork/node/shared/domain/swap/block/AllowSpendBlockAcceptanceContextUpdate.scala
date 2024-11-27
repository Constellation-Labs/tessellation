package io.constellationnetwork.node.shared.domain.swap.block

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.swap.AllowSpendReference

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._

@derive(eqv, show)
case class AllowSpendBlockAcceptanceContextUpdate(
  balances: Map[Address, Balance],
  lastTxRefs: Map[Address, AllowSpendReference]
)

object AllowSpendBlockAcceptanceContextUpdate {

  val empty: AllowSpendBlockAcceptanceContextUpdate = AllowSpendBlockAcceptanceContextUpdate(
    Map.empty,
    Map.empty
  )
}
