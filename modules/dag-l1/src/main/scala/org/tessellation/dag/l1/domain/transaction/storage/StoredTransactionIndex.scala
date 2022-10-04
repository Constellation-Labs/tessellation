package org.tessellation.dag.l1.domain.transaction.storage

import org.tessellation.dag.l1.rosetta.model.network.NetworkStatus
import org.tessellation.schema.address.Address
import org.tessellation.security.hash.Hash

case class StoredTransactionIndex(
  hash: Hash,
  sourceAddress: Address,
  destinationAddress: Address,
  height: Long,
  networkStatus: NetworkStatus,
  transactionBytes: Array[Byte]
)
