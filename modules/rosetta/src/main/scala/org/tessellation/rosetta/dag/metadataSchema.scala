package org.tessellation.rosetta.api.dag

object metadataSchema {

  case class GenericMetadata()

  case class AccountBalanceResponseMetadata(sequenceNum: Int)

  case class AccountCoinsResponseMetadata(sequenceNum: Int)

  case class AccountIdentifierMetadata(alias: Option[String])

  case class AmountMetadata()

  case class BlockMetadata()

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

}
