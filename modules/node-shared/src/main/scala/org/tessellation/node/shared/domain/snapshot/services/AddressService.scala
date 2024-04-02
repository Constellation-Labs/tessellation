package org.tessellation.node.shared.domain.snapshot.services

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.snapshot.Snapshot

trait AddressService[F[_], S <: Snapshot] {
  def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]]
  def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]]
  def getFilteredOutTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]]
  def getWalletCount: F[Option[(Int, SnapshotOrdinal)]]
}
