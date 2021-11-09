package org.tessellation.domain.cluster.storage

import org.tessellation.infrastructure.db.schema.StoredAddress
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

sealed trait DBUpsertAction[A] { val value: A }
case class Insert[A](value: A) extends DBUpsertAction[A]
case class Update[A](value: A) extends DBUpsertAction[A]

trait AddressStorage[F[_]] {
  def getBalance(address: Address): F[Balance]

  def getMaybeBalance(address: Address): F[Option[Balance]]

  def updateBalance(address: Address, balance: Balance): F[(Address, Balance)]

  def updateBatchBalances(addressBalances: Seq[DBUpsertAction[StoredAddress]]): F[Unit]

  def clearBalance(address: Address): F[Unit]
}
