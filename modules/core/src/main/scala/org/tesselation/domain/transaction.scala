package org.tesselation.domain

import org.tesselation.domain.address.Address

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object transaction {

  @derive(decoder, encoder, eqv, show)
  case class TransactionAmount(value: Long)

  @derive(decoder, encoder, eqv, show)
  case class TransactionFee(value: Long)

  @derive(decoder, encoder, eqv, show)
  case class Transaction(
    src: Address,
    dst: Address,
    amount: TransactionAmount,
    fee: Option[TransactionFee] = None
  )
}
