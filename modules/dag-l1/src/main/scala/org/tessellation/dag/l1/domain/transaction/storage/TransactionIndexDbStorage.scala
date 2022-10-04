package org.tessellation.dag.l1.domain.transaction.storage

import org.tessellation.dag.l1.rosetta.model.network.NetworkStatus
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.Height
import org.tessellation.security.hash.Hash

trait TransactionIndexStorage[F[_]] {

  def updateStoredTransactionIndexValues(
    values: Map[Hash, (Address, Address, Height, NetworkStatus, Array[Byte])]
  ): F[Unit]

  def getTransactionIndexValuesAnd(
    id: Option[Hash],
    address: Option[Address],
    networkStatus: Option[NetworkStatus],
    maxHeight: Option[Long],
    offset: Option[Int],
    limit: Option[Int]
  ): Either[String, F[List[SignedTransactionIndexEntry]]]

  def getTransactionIndexValuesOr(
    id: Option[Hash],
    address: Option[Address],
    networkStatus: Option[NetworkStatus],
    maxHeight: Option[Long],
    offset: Option[Int],
    limit: Option[Int]
  ): Either[String, F[List[SignedTransactionIndexEntry]]]
}
