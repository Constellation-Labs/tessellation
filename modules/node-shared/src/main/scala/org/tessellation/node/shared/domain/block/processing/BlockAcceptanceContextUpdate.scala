package org.tessellation.node.shared.domain.block.processing

import org.tessellation.schema.BlockReference
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.swap.AllowSpendReference
import org.tessellation.schema.transaction.TransactionReference

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceContextUpdate(
  balances: Map[Address, Balance],
  lastTxRefs: Map[Address, TransactionReference],
  parentUsages: Map[BlockReference, NonNegLong],
  lastAllowSpendRefs: Option[Map[Address, AllowSpendReference]]
)

object BlockAcceptanceContextUpdate {

  val empty: BlockAcceptanceContextUpdate = BlockAcceptanceContextUpdate(
    Map.empty,
    Map.empty,
    Map.empty,
    None
  )
}
