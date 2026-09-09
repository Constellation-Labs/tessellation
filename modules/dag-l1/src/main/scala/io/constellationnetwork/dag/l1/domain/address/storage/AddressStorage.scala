package io.constellationnetwork.dag.l1.domain.address.storage

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance

trait AddressStorage[F[_]] {
  def getState: F[Map[Address, Balance]]
  def getBalance(address: Address): F[Balance]
  def updateBalances(addressBalances: Map[Address, Balance]): F[Unit]

  /** Atomically replace the entire balance map in a single operation.
    *
    * This is the crash-/race-safe form of `clean >> updateBalances(m)`: that two-op sequence allows a concurrent `updateBalances(delta)`
    * (from the live block-acceptance path) to interleave between the `clean` and the update, leaving a stray `delta` balance that survives
    * every subsequent incremental alignment. `replaceAll` closes that window structurally, so two nodes applying the same global snapshot
    * converge to identical balances.
    */
  def replaceAll(addressBalances: Map[Address, Balance]): F[Unit]
  def clean: F[Unit]
}
