package org.tesselation.schema

import cats.{Eq, Show}

import org.tesselation.ext.refined._
import org.tesselation.schema.balance.Balance
import org.tesselation.security.Base58

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.refineV
import io.circe.{Decoder, Encoder}
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import io.getquill.MappedEncoding

object address {

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class Address(value: DAGAddress)

  object Address {
    implicit val quillEncode: MappedEncoding[Address, String] = MappedEncoding[Address, String](_.coerce.value)
    implicit val quillDecode: MappedEncoding[String, Address] =
      MappedEncoding[String, Address](
        refineV[DAGAddressRefined].apply[String](_) match {
          // TODO: don't like it though an example with java.util.UUID.fromString in Quills docs suggests you can throw
          //  like this, should be possible to do it better
          case Left(e)  => throw new Throwable(e)
          case Right(a) => Address(a)
        }
      )

    implicit val decodeDAGAddress: Decoder[DAGAddress] =
      decoderOf[String, DAGAddressRefined]

    implicit val encodeDAGAddress: Encoder[DAGAddress] =
      encoderOf[String, DAGAddressRefined]

    implicit val eqDAGAddress: Eq[DAGAddress] =
      eqOf[String, DAGAddressRefined]

    implicit val showDAGAddress: Show[DAGAddress] =
      showOf[String, DAGAddressRefined]
  }

  case class AddressCache(balance: Balance)

  final case class DAGAddressRefined()

  object DAGAddressRefined {
    implicit def addressCorrectValidate: Validate.Plain[String, DAGAddressRefined] =
      Validate.fromPredicate(
        {
          case a if a == StardustCollective.address => true
          case a if a.length != 40                  => false
          case a =>
            val par = a.substring(4).filter(Character.isDigit).map(_.toString.toInt).sum % 9

            val isBase58 = Base58.isBase58(a.substring(4))
            val hasDAGPrefixAndParity = a.startsWith(s"DAG$par")

            isBase58 && hasDAGPrefixAndParity
        },
        a => s"Invalid DAG address: $a",
        DAGAddressRefined()
      )
  }

  type DAGAddress = String Refined DAGAddressRefined
}
