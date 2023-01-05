package org.tessellation.rosetta.server.model.dag

import derevo.cats.{eqv, show}
import derevo.derive
import enumeratum.{Enum, EnumEntry}
import io.circe.Encoder

/** Represents schema changes specific to this project related to metadata or type Any fields which do not properly code generate
  */
object schema {

  case class GenericMetadata()

  @derive(eqv, show)
  sealed trait SignatureType extends EnumEntry

  object SignatureType extends Enum[SignatureType] with SignatureTypeEncoder {
    val values = findValues

    case object Ecdsa extends SignatureType
    case object EcdsaRecovery extends SignatureType
    case object Ed25519 extends SignatureType
    case object Schnorr1 extends SignatureType
    case object SchnorrPoseidon extends SignatureType
  }

  trait SignatureTypeEncoder {
    implicit val encode: Encoder[SignatureType] =
      Encoder.encodeString.contramap[SignatureType](_ match {
        case SignatureType.EcdsaRecovery   => "ecdsa_recovery"
        case SignatureType.Schnorr1        => "schnorr_1"
        case SignatureType.SchnorrPoseidon => "schnorr_poseidon"
        case signatureTypeValue            => signatureTypeValue.toString.toLowerCase
      })
  }

  @derive(eqv, show)
  sealed trait ChainObjectStatus extends EnumEntry

  object ChainObjectStatus extends Enum[ChainObjectStatus] with ChainObjectStatusEncoder {
    case object Unknown extends ChainObjectStatus
    case object Pending extends ChainObjectStatus
    case object Accepted extends ChainObjectStatus

    override def values: IndexedSeq[ChainObjectStatus] = findValues
  }

  trait ChainObjectStatusEncoder {
    implicit val encode: Encoder[ChainObjectStatus] =
      Encoder.encodeString.contramap[ChainObjectStatus](_.toString)
  }

  case class NetworkChainObjectStatus(value: ChainObjectStatus)

  case class AccountBalanceResponseMetadata(sequenceNum: Int)

  case class AccountCoinsResponseMetadata(sequenceNum: Int)

  case class AccountIdentifierMetadata(alias: Option[String])

  case class AmountMetadata()

  case class BlockMetadata()

  case class CallResponseActual()

  case class ConstructionDeriveRequestMetadata()

  case class ConstructionDeriveResponseMetadata()

  case class ConstructionMetadataRequestOptions()

  case class ConstructionMetadataResponseMetadata(
    lastTransactionHashReference: String,
    lastTransactionOrdinalReference: Long
  )

  case class ConstructionParseResponseMetadata()

  case class ConstructionPayloadsRequestMetadata()

  case class ConstructionPreprocessRequestMetadata()

  case class ErrorDetailKeyValue(name: String, value: String)

  case class ErrorDetails(details: List[ErrorDetailKeyValue])

}
