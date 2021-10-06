package org.tesselation.schema

import scala.util.control.NoStackTrace

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype
import io.getquill.MappedEncoding

object address {

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class Address(value: String)

  object Address {
    implicit val quillEncode: MappedEncoding[Address, String] = MappedEncoding[Address, String](_.value)
    implicit val quillDecode: MappedEncoding[String, Address] = MappedEncoding[String, Address](Address(_))
  }

  @derive(decoder, encoder)
  case class InvalidAddress(value: String) extends NoStackTrace

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class Balance(value: Long)

  object Balance {
    implicit val quillEncode: MappedEncoding[Balance, Long] = MappedEncoding[Balance, Long](_.value)
    implicit val quillDecode: MappedEncoding[Long, Balance] = MappedEncoding[Long, Balance](Balance(_))
  }

  case class AddressCache(
    balance: Balance
  )

}
