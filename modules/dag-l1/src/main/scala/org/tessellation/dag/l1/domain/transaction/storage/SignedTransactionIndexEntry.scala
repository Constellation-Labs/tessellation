package org.tessellation.dag.l1.domain.transaction.storage

import org.tessellation.dag.l1.rosetta.model.network.NetworkStatus
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

case class SignedTransactionIndexEntry(
  signedTransaction: Signed[Transaction],
  height: Long,
  networkStatus: NetworkStatus
)
