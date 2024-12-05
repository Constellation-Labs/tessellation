package io.constellationnetwork.node.shared.domain.tokenlock.block

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.tokenLock.TokenLockReference

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class TokenLockBlockAcceptanceContextUpdate(
  balances: Map[Address, Balance],
  lastTokenLocksRefs: Map[Address, TokenLockReference]
)

object TokenLockBlockAcceptanceContextUpdate {

  val empty: TokenLockBlockAcceptanceContextUpdate = TokenLockBlockAcceptanceContextUpdate(
    Map.empty,
    Map.empty
  )
}
