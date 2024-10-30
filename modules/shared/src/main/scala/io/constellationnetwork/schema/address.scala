package io.constellationnetwork.schema

import io.constellationnetwork.ext.cats.data.OrderBasedOrdering
import io.constellationnetwork.ext.refined._
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.security.Base58
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia._
import derevo.derive
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.cats._
import eu.timepit.refined.refineV
import io.circe._
import io.estatico.newtype.macros.newtype

object address {

  @derive(decoder, encoder, keyDecoder, keyEncoder, order, show, eqv)
  @newtype
  case class Address(value: DAGAddress)

  object Address {

    def fromBytes(bytes: Array[Byte]): Address = {
      val sha256Digest = Hash.sha256DigestFromBytes(bytes)
      val encoded = Base58.encode(sha256Digest.toIndexedSeq)
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
