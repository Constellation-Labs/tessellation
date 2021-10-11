package org.tesselation.infrastructure.genesis

import org.tesselation.schema.address.{Address, Balance}

import derevo.cats.{eqv, show}
import derevo.derive
import fs2.data.csv.RowDecoder
import fs2.data.csv.generic.semiauto.deriveRowDecoder

object types {

  @derive(eqv, show)
  case class GenesisAccount(address: Address, balance: Balance)

  case class GenesisCSVAccount(address: String, balance: Long) {
    def toGenesisAccount: GenesisAccount = GenesisAccount(Address(address), Balance(balance))
  }

  object GenesisCSVAccount {
    implicit val rowDecoder: RowDecoder[GenesisCSVAccount] = deriveRowDecoder
  }
}
