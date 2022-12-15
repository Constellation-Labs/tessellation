package org.tessellation.rosetta.server.model

import org.tessellation.schema.transaction.Transaction

object RosettaSearch {
  case class BlockSearchRequest(
    isOr: Boolean,
    isAnd: Boolean,
    addressOpt: Option[String],
    networkStatus: Option[String],
    limit: Option[Long],
    offset: Option[Long],
    transactionHash: Option[String],
    maxBlock: Option[Long]
  )

  def searchBySourceAddress(transactionHash: String, snapshots: List[Transaction]) =
    snapshots.filter(_.source == transactionHash)

  def searchByDestinationAddress(transactionHash: String, snapshots: List[Transaction]) =
    snapshots.filter(_.destination == transactionHash)

  def searchByAddress(transactionHash: String, snapshots: List[Transaction]) = {
    val sourceTransactions = searchBySourceAddress(transactionHash, snapshots)
    val destinationTransactions = searchByDestinationAddress(transactionHash, snapshots)

    sourceTransactions ++ destinationTransactions
  }

}
