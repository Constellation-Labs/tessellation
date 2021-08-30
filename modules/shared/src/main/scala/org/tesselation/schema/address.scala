package org.tesselation.schema

import scala.util.control.NoStackTrace

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object address {

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class Address(value: String)

  @derive(decoder, encoder)
  case class InvalidAddress(value: String) extends NoStackTrace

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class Balance(value: Long)

  case class AddressCache(
    balance: Balance
  )

}
