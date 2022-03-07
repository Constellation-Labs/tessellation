package org.tessellation.infrastructure.genesis

import org.tessellation.schema.address.{Address, DAGAddressRefined}
import org.tessellation.schema.balance.Balance

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import fs2.data.csv.RowDecoder
import fs2.data.csv.generic.semiauto.deriveRowDecoder

object types {

  @derive(eqv, show)
  case class GenesisAccount(address: Address, balance: Balance)

  case class GenesisCSVAccount(address: String, balance: Long) {

    def toGenesisAccount: Either[String, GenesisAccount] =
      for {
        dagAddress <- refineV[DAGAddressRefined](address)
        nonNegLong <- refineV[NonNegative](balance)
      } yield GenesisAccount(Address(dagAddress), Balance(nonNegLong))
  }

  object GenesisCSVAccount {
    implicit val rowDecoder: RowDecoder[GenesisCSVAccount] = deriveRowDecoder
  }
}
