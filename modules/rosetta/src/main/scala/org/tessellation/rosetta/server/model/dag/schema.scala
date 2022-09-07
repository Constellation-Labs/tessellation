package org.tessellation.rosetta.server.model.dag

import derevo.cats.eqv
import derevo.derive
import io.circe.Encoder
import io.estatico.newtype.macros.newtype

/**
  * Represents schema changes specific to this project related to metadata
  * or type Any fields which do not properly code generate
  */
object schema {

  case class GenericMetadata()

  // Decoders
//
//  implicit val myCustomDecoder: Decoder[NetworkRequest] =
//    deriveDecoder[NetworkRequest](io.circe.derivation.renaming.snakeCase)

  // Enum types

  @derive(eqv)
  sealed trait ChainObjectStatus

  object ChainObjectStatus {
    case object Unknown extends ChainObjectStatus
    case object Pending extends ChainObjectStatus
    case object Accepted extends ChainObjectStatus
    implicit val jsonEncoder: Encoder[ChainObjectStatus] =
      Encoder.forProduct1("status")(_.toString)
  }

//  @derive(encoder)
  @newtype
  case class NetworkChainObjectStatus(value: ChainObjectStatus)
//
//  @derive(encoder)
//  case class AppStatus(
//    fooStatus: FooStatus
//  )

  case class AccountBalanceResponseMetadata(sequenceNum: Int)

  case class AccountCoinsResponseMetadata(sequenceNum: Int)

  case class AccountIdentifierMetadata(alias: Option[String])

  case class AmountMetadata()

  case class BlockMetadata()

  case class CallRequestParametersExampleEndpoint1(
    paramOne: String
  )

  case class CallRequestParametersExampleEndpoint2(
    paramOne: Int
  )

  case class CallRequestParameters(
    callRequestParametersExampleEndpoint1: Option[CallRequestParametersExampleEndpoint1],
    callRequestParametersExampleEndpoint2: Option[CallRequestParametersExampleEndpoint2]
  )

  case class CallResponseExampleEndpoint1(
    valueOne: String
  )

  case class CallResponseExampleEndpoint2(
    valueOne: Int
  )

  case class CallResponseActual(
//    callResponseExampleEndpoint1: Option[CallResponseExampleEndpoint1],
//    callResponseExampleEndpoint2: Option[CallResponseExampleEndpoint2]
  )

  case class ConstructionDeriveRequestMetadata()

  case class ConstructionDeriveResponseMetadata()

  case class ConstructionMetadataRequestOptions()

  case class ConstructionMetadataResponseMetadata(
    lastTransactionHashReference: String,
    lastTransactionOrdinalReference: Long
  )

  case class ConstructionParseResponseMetadata()

  case class ConstructionPayloadsRequestMetadata(
    srcAddress: String,
    lastTransactionHashReference: String,
    lastTransactionOrdinalReference: Long,
    fee: Long,
    salt: Option[Long]
  )

  case class ConstructionPreprocessRequestMetadata()

  case class ErrorDetailKeyValue(name: String, value: String)

  case class ErrorDetails(details: List[ErrorDetailKeyValue])

}
