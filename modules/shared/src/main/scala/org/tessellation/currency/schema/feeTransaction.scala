package org.tessellation.currency.schema

import org.tessellation.ext.derevo.ordering
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.transaction.{TransactionOrdinal, TransactionSalt}
import org.tessellation.security.hash.Hash

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
