package org.tessellation.dag.l0.domain

import org.tessellation.ext.http4s.queryParam
import org.tessellation.node.shared.ext.http4s.refined._
import org.tessellation.schema.address.{Address, DAGAddress}

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import io.circe.refined._
import io.circe.{Decoder, Encoder}
import io.estatico.newtype.macros.newtype

object address {

  @derive(queryParam, show)
  @newtype
  case class AddressParam(value: DAGAddress) {
    def toDomain: Address = Address(value)
  }

  object AddressParam {
    implicit val jsonEncoder: Encoder[AddressParam] =
      Encoder.forProduct1("address")(_.value)

    implicit val jsonDecoder: Decoder[AddressParam] =
      Decoder.forProduct1("address")(AddressParam.apply)
  }
}
