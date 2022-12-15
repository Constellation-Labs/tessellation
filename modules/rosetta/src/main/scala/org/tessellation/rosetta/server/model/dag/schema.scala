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
  sealed trait ChainObjectStatus extends EnumEntry

  object ChainObjectStatus extends Enum[ChainObjectStatus] with ChainObjectStatusCodecs {
    case object Unknown extends ChainObjectStatus
    case object Pending extends ChainObjectStatus
    case object Accepted extends ChainObjectStatus

    override def values: IndexedSeq[ChainObjectStatus] = findValues
  }

  trait ChainObjectStatusCodecs {
    implicit val encode: Encoder[ChainObjectStatus] =
      Encoder.forProduct1("status")(_.toString)
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
