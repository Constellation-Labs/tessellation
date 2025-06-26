package io.constellationnetwork.node.shared.domain.snapshot.services

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.snapshot.Snapshot

trait AddressService[F[_], S <: Snapshot] {
  def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]]
  def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]]
  def getCirculatedSupply: F[Option[(BigInt, SnapshotOrdinal)]]
  def getWalletCount: F[Option[(Int, SnapshotOrdinal)]]
}
