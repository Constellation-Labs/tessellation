package io.constellationnetwork.currency.schema

import cats.effect.Async
import cats.syntax.functor._

import io.constellationnetwork.currency.dataApplication.DataUpdate
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.security.hash.Hash

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
