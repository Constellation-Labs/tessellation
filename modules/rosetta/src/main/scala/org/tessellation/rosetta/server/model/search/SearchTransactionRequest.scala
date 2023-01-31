package org.tessellation.rosetta.server.model.search

import org.tessellation.schema.address.Address
import org.tessellation.security.hash.Hash

import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}

/** This is an intermediary object used to hold the values from the Rosetta request that is relevant to our system. It is used by the
  * https://www.rosetta-api.org/docs/SearchApi.html endpoints.
  */

case class SearchTransactionRequest(
  operation: Operator,
  address: Option[Address],
  status: Option[OperationStatus],
  limit: Option[PosLong],
  offset: Option[PosLong],
  transactionHash: Option[Hash],
  maxBlock: Option[NonNegLong]
)
