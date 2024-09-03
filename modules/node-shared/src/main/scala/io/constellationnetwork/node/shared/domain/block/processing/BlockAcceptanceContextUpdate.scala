package io.constellationnetwork.node.shared.domain.block.processing

import io.constellationnetwork.schema.BlockReference
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.transaction.TransactionReference

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceContextUpdate(
  balances: Map[Address, Balance],
  lastTxRefs: Map[Address, TransactionReference],
  parentUsages: Map[BlockReference, NonNegLong]
)

object BlockAcceptanceContextUpdate {

  val empty: BlockAcceptanceContextUpdate = BlockAcceptanceContextUpdate(
    Map.empty,
    Map.empty,
    Map.empty
  )
}
