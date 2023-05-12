package org.tessellation.schema

import scala.collection.immutable.SortedMap

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.TransactionReference

case class CommonSnapshotInfo(
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance]
)
