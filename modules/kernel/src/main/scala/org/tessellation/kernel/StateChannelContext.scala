package org.tessellation.kernel

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

import fs2.Stream

trait StateChannelContext[F[_]] {

  val address: Address

  val inputs: Stream[F, Ω]

  def enqueueInput(input: Ω): F[Unit]

  def getBalance(address: Address): F[Balance]

  def setBalance(address: Address, balance: Balance): F[Unit]

  // TODO: @mwadon - probably transfer(from: Address, to: Address, balance: Balance) needed

}
