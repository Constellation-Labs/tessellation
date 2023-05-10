package org.tessellation.schema

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.refined._
import org.tessellation.schema.balance.Balance
import org.tessellation.security.Base58
import org.tessellation.security.hash.Hash

import derevo.cats.{order, show}
import derevo.circe.magnolia._
import derevo.derive
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.cats._
import eu.timepit.refined.refineV
import io.circe._
import io.estatico.newtype.macros.newtype

object address {

  @derive(decoder, encoder, keyDecoder, keyEncoder, order, show)
  @newtype
  case class Address(value: DAGAddress)

  object Address {

    def fromBytes(bytes: Array[Byte]): Address = {
      val hashCode = Hash.hashCodeFromBytes(bytes)
      val encoded = Base58.encode(hashCode.asBytes().toIndexedSeq)
      val end = encoded.slice(encoded.length - 36, encoded.length)
      val validInt = end.filter(Character.isDigit)
      val ints = validInt.map(_.toString.toInt)
      val sum = ints.sum
      val par = sum % 9
      val res2: DAGAddress = refineV[DAGAddressRefined].unsafeFrom(s"DAG$par$end")
      Address(res2)
    }

    implicit object OrderingInstance extends OrderBasedOrdering[Address]

    implicit val decodeDAGAddress: Decoder[DAGAddress] =
      decoderOf[String, DAGAddressRefined]

    implicit val encodeDAGAddress: Encoder[DAGAddress] =
      encoderOf[String, DAGAddressRefined]

    implicit val keyDecodeDAGAddress: KeyDecoder[DAGAddress] = new KeyDecoder[DAGAddress] {
      def apply(key: String): Option[DAGAddress] = refineV[DAGAddressRefined](key).toOption
    }

    implicit val keyEncodeDAGAddress: KeyEncoder[DAGAddress] = new KeyEncoder[DAGAddress] {
      def apply(key: DAGAddress): String = key.value
    }
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
