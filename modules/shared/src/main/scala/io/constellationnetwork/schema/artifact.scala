package io.constellationnetwork.schema

import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.{CurrencyId, SwapAmount}
import io.constellationnetwork.schema.tokenLock.{TokenLockAmount, TokenLockReference}
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object artifact {
  @derive(decoder, encoder, order, ordering, show)
  sealed trait SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  case class SpendAction(input: SpendTransaction, output: SpendTransaction) extends SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  case class SpendTransaction(
    allowSpendRef: Option[Hash],
    currency: Option[CurrencyId],
    amount: SwapAmount,
    destination: Address
  )

  @derive(decoder, encoder, order, ordering, show)
  case class TokenUnlock(
    lockReference: TokenLockReference,
    amount: TokenLockAmount,
    currencyId: Option[CurrencyId],
    address: Address
  ) extends SharedArtifact

  @derive(decoder, encoder, order, ordering, show)
  case class AllowSpendExpiration(
    allowSpendRef: Hash
  ) extends SharedArtifact
}
