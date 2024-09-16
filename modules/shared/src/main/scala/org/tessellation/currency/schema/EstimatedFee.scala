package org.tessellation.currency.schema

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.currency.dataApplication.DataUpdate
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.security.hash.Hash

import derevo.cats.show
import derevo.derive

@derive(show)
sealed trait EstimatedFee

object EstimatedFee {
  case object Zero extends EstimatedFee
  case class Estimated(fee: Amount, address: Address) extends EstimatedFee

  val empty: EstimatedFee = Zero

  def apply(fee: Amount, address: Address): EstimatedFee =
    Estimated(fee, address)

  def getUpdateHash[F[_]: Async](dataUpdate: DataUpdate, toBytes: DataUpdate => F[Array[Byte]]): F[Hash] =
    toBytes(dataUpdate).map(Hash.fromBytes)
}
