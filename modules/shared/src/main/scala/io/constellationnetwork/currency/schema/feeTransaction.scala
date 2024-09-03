package io.constellationnetwork.currency.schema

import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.transaction.{TransactionOrdinal, TransactionSalt}
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object feeTransaction {

  @derive(decoder, encoder, order, show)
  case class FeeTransactionReference(ordinal: TransactionOrdinal, hash: Hash)

  @derive(decoder, encoder, order, ordering, show)
  case class FeeTransaction(
    source: Address,
    destination: Address,
    amount: Amount,
    parent: FeeTransactionReference,
    salt: TransactionSalt
  ) {

    val ordinal: TransactionOrdinal = parent.ordinal.next
  }
}
