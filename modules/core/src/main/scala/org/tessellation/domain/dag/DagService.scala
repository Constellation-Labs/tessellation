package org.tessellation.domain.dag

import org.tessellation.dag.snapshot.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

trait DAGService[F[_]] {
  def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]]
  def getBalance(ordinal: SnapshotOrdinal, address: Address): F[Option[(Balance, SnapshotOrdinal)]]

  def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]]
  def getTotalSupply(ordinal: SnapshotOrdinal): F[Option[(BigInt, SnapshotOrdinal)]]

  def getWalletCount: F[Option[(Int, SnapshotOrdinal)]]
  def getWalletCount(ordinal: SnapshotOrdinal): F[Option[(Int, SnapshotOrdinal)]]
}
