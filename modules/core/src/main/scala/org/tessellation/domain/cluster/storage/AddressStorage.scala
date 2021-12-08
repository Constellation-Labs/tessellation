package org.tessellation.domain.cluster.storage

import org.tessellation.infrastructure.db.schema.StoredAddress
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

sealed trait UpsertAction[A] { val value: A }
case class Insert[A](value: A) extends UpsertAction[A]
case class Update[A](value: A) extends UpsertAction[A]

trait AddressStorage[F[_]] {
  def getBalance(address: Address): F[Balance]

  def getMaybeBalance(address: Address): F[Option[Balance]]

  def updateBalance(address: Address, balance: Balance): F[(Address, Balance)]

  def updateBatchBalances(addressBalances: Seq[UpsertAction[StoredAddress]]): F[Unit]

  def clearBalance(address: Address): F[Unit]
}
