package org.tessellation.rosetta.server.model.dag

import org.tessellation.rosetta.server.model._
import org.tessellation.rosetta.server.model.dag.schema._

/**
  * This whole thing has to be manually re-arranged after generating because of
  * implicit dependencies requiring to be in order.
  */
object decoders {

  import io.circe.magnolia.configured.decoder.semiauto._
  import io.circe.magnolia.configured.encoder.semiauto._
  import io.circe.magnolia.configured.Configuration

  implicit val configuration: Configuration = Configuration.default.withSnakeCaseMemberNames

  implicit lazy val CoinIdentifierEncoder = deriveConfiguredMagnoliaEncoder[CoinIdentifier]
  implicit lazy val CoinIdentifierDecoder = deriveConfiguredMagnoliaDecoder[CoinIdentifier]
  implicit lazy val ModelCaseEncoder = deriveConfiguredMagnoliaEncoder[ModelCase]
  implicit lazy val ModelCaseDecoder = deriveConfiguredMagnoliaDecoder[ModelCase]
  implicit lazy val ExemptionTypeEncoder = deriveConfiguredMagnoliaEncoder[ExemptionType]
  implicit lazy val ExemptionTypeDecoder = deriveConfiguredMagnoliaDecoder[ExemptionType]
  implicit lazy val OperatorEncoder = deriveConfiguredMagnoliaEncoder[Operator]
  implicit lazy val OperatorDecoder = deriveConfiguredMagnoliaDecoder[Operator]
  implicit lazy val OperationIdentifierEncoder = deriveConfiguredMagnoliaEncoder[OperationIdentifier]
  implicit lazy val OperationIdentifierDecoder = deriveConfiguredMagnoliaDecoder[OperationIdentifier]
  implicit lazy val BlockEventTypeEncoder = deriveConfiguredMagnoliaEncoder[BlockEventType]
  implicit lazy val BlockEventTypeDecoder = deriveConfiguredMagnoliaDecoder[BlockEventType]
  implicit lazy val GenericMetadataEncoder = deriveConfiguredMagnoliaEncoder[GenericMetadata]
  implicit lazy val GenericMetadataDecoder = deriveConfiguredMagnoliaDecoder[GenericMetadata]
  implicit lazy val SignatureTypeEncoder = deriveConfiguredMagnoliaEncoder[SignatureType]
  implicit lazy val SignatureTypeDecoder = deriveConfiguredMagnoliaDecoder[SignatureType]
  implicit lazy val BlockIdentifierEncoder = deriveConfiguredMagnoliaEncoder[BlockIdentifier]
  implicit lazy val BlockIdentifierDecoder = deriveConfiguredMagnoliaDecoder[BlockIdentifier]
  implicit lazy val CurrencyEncoder = deriveConfiguredMagnoliaEncoder[Currency]
  implicit lazy val CurrencyDecoder = deriveConfiguredMagnoliaDecoder[Currency]
  implicit lazy val DirectionEncoder = deriveConfiguredMagnoliaEncoder[Direction]
  implicit lazy val DirectionDecoder = deriveConfiguredMagnoliaDecoder[Direction]
  implicit lazy val PartialBlockIdentifierEncoder = deriveConfiguredMagnoliaEncoder[PartialBlockIdentifier]
  implicit lazy val PartialBlockIdentifierDecoder = deriveConfiguredMagnoliaDecoder[PartialBlockIdentifier]
  implicit lazy val CurveTypeEncoder = deriveConfiguredMagnoliaEncoder[CurveType]
  implicit lazy val CurveTypeDecoder = deriveConfiguredMagnoliaDecoder[CurveType]
  implicit lazy val OperationStatusEncoder = deriveConfiguredMagnoliaEncoder[OperationStatus]
  implicit lazy val OperationStatusDecoder = deriveConfiguredMagnoliaDecoder[OperationStatus]
  implicit lazy val VersionEncoder = deriveConfiguredMagnoliaEncoder[Version]
  implicit lazy val VersionDecoder = deriveConfiguredMagnoliaDecoder[Version]

  implicit lazy val PublicKeyEncoder = deriveConfiguredMagnoliaEncoder[PublicKey]
  implicit lazy val PublicKeyDecoder = deriveConfiguredMagnoliaDecoder[PublicKey]

  implicit lazy val SubNetworkIdentifierEncoder = deriveConfiguredMagnoliaEncoder[SubNetworkIdentifier]
  implicit lazy val SubNetworkIdentifierDecoder = deriveConfiguredMagnoliaDecoder[SubNetworkIdentifier]

  implicit lazy val NetworkIdentifierEncoder = deriveConfiguredMagnoliaEncoder[NetworkIdentifier]
  implicit lazy val NetworkIdentifierDecoder = deriveConfiguredMagnoliaDecoder[NetworkIdentifier]

  implicit lazy val BalanceExemptionEncoder = deriveConfiguredMagnoliaEncoder[BalanceExemption]
  implicit lazy val BalanceExemptionDecoder = deriveConfiguredMagnoliaDecoder[BalanceExemption]
  implicit lazy val ErrorDetailsKVEncoder = deriveConfiguredMagnoliaEncoder[ErrorDetailKeyValue]
  implicit lazy val ErrorDetailsKVDecoder = deriveConfiguredMagnoliaDecoder[ErrorDetailKeyValue]
  implicit lazy val ErrorDetailsEncoder = deriveConfiguredMagnoliaEncoder[ErrorDetails]
  implicit lazy val ErrorDetailsDecoder = deriveConfiguredMagnoliaDecoder[ErrorDetails]
  implicit lazy val ErrorEncoder = deriveConfiguredMagnoliaEncoder[Error]
  implicit lazy val ErrorDecoder = deriveConfiguredMagnoliaDecoder[Error]
  implicit lazy val AllowEncoder = deriveConfiguredMagnoliaEncoder[Allow]
  implicit lazy val AllowDecoder = deriveConfiguredMagnoliaDecoder[Allow]
  implicit lazy val SubAccountIdentifierEncoder = deriveConfiguredMagnoliaEncoder[SubAccountIdentifier]
  implicit lazy val SubAccountIdentifierDecoder = deriveConfiguredMagnoliaDecoder[SubAccountIdentifier]
  implicit lazy val AccountIdentifierMEncoder = deriveConfiguredMagnoliaEncoder[AccountIdentifierMetadata]
  implicit lazy val AccountIdentifierMDecoder = deriveConfiguredMagnoliaDecoder[AccountIdentifierMetadata]

  implicit lazy val AccountIdentifierEncoder = deriveConfiguredMagnoliaEncoder[AccountIdentifier]
  implicit lazy val AccountIdentifierDecoder = deriveConfiguredMagnoliaDecoder[AccountIdentifier]
  implicit lazy val AmountEncoder = deriveConfiguredMagnoliaEncoder[Amount]
  implicit lazy val AmountDecoder = deriveConfiguredMagnoliaDecoder[Amount]
  implicit lazy val CoinActionEncoder = deriveConfiguredMagnoliaEncoder[CoinAction]
  implicit lazy val CoinActionDecoder = deriveConfiguredMagnoliaDecoder[CoinAction]
  implicit lazy val CoinChangeEncoder = deriveConfiguredMagnoliaEncoder[CoinChange]
  implicit lazy val CoinChangeDecoder = deriveConfiguredMagnoliaDecoder[CoinChange]

  implicit lazy val OperationEncoder = deriveConfiguredMagnoliaEncoder[Operation]
  implicit lazy val OperationDecoder = deriveConfiguredMagnoliaDecoder[Operation]

  implicit lazy val TransactionIdentifierEncoder = deriveConfiguredMagnoliaEncoder[TransactionIdentifier]
  implicit lazy val TransactionIdentifierDecoder = deriveConfiguredMagnoliaDecoder[TransactionIdentifier]

  implicit lazy val SigningPayloadEncoder = deriveConfiguredMagnoliaEncoder[SigningPayload]
  implicit lazy val SigningPayloadDecoder = deriveConfiguredMagnoliaDecoder[SigningPayload]

  implicit lazy val ConstructionParseRequestEncoder = deriveConfiguredMagnoliaEncoder[ConstructionParseRequest]
  implicit lazy val ConstructionParseRequestDecoder = deriveConfiguredMagnoliaDecoder[ConstructionParseRequest]

  implicit lazy val MetadataRequestEncoder = deriveConfiguredMagnoliaEncoder[MetadataRequest]
  implicit lazy val MetadataRequestDecoder = deriveConfiguredMagnoliaDecoder[MetadataRequest]

  implicit lazy val RelatedTransactionEncoder = deriveConfiguredMagnoliaEncoder[RelatedTransaction]
  implicit lazy val RelatedTransactionDecoder = deriveConfiguredMagnoliaDecoder[RelatedTransaction]
  implicit lazy val TransactionEncoder = deriveConfiguredMagnoliaEncoder[Transaction]
  implicit lazy val TransactionDecoder = deriveConfiguredMagnoliaDecoder[Transaction]

  implicit lazy val BlockTransactionEncoder = deriveConfiguredMagnoliaEncoder[BlockTransaction]
  implicit lazy val BlockTransactionDecoder = deriveConfiguredMagnoliaDecoder[BlockTransaction]

//  implicit lazy val networkChainObjectStatus = deriveConfiguredMagnoliaEncoder[NetworkChainObjectStatus]

  implicit lazy val SearchTransactionsRequestEncoder = deriveConfiguredMagnoliaEncoder[SearchTransactionsRequest]
  implicit lazy val SearchTransactionsRequestDecoder = deriveConfiguredMagnoliaDecoder[SearchTransactionsRequest]
  implicit lazy val NetworkOptionsResponseEncoder = deriveConfiguredMagnoliaEncoder[NetworkOptionsResponse]
  implicit lazy val NetworkOptionsResponseDecoder = deriveConfiguredMagnoliaDecoder[NetworkOptionsResponse]

  implicit lazy val ConstructionPayloadsRequestMEncoder =
    deriveConfiguredMagnoliaEncoder[ConstructionPayloadsRequestMetadata]
  implicit lazy val ConstructionPayloadsRequestMDecoder =
    deriveConfiguredMagnoliaDecoder[ConstructionPayloadsRequestMetadata]
  implicit lazy val ConstructionPayloadsRequestEncoder = deriveConfiguredMagnoliaEncoder[ConstructionPayloadsRequest]
  implicit lazy val ConstructionPayloadsRequestDecoder = deriveConfiguredMagnoliaDecoder[ConstructionPayloadsRequest]

  implicit lazy val MempoolResponseEncoder = deriveConfiguredMagnoliaEncoder[MempoolResponse]
  implicit lazy val MempoolResponseDecoder = deriveConfiguredMagnoliaDecoder[MempoolResponse]
  implicit lazy val AccountBalanceRequestEncoder = deriveConfiguredMagnoliaEncoder[AccountBalanceRequest]
  implicit lazy val AccountBalanceRequestDecoder = deriveConfiguredMagnoliaDecoder[AccountBalanceRequest]

  implicit lazy val ConstructionDeriveResponseEncoder = deriveConfiguredMagnoliaEncoder[ConstructionDeriveResponse]
  implicit lazy val ConstructionDeriveResponseDecoder = deriveConfiguredMagnoliaDecoder[ConstructionDeriveResponse]
  implicit lazy val ConstructionPreprocessRequestEncoder =
    deriveConfiguredMagnoliaEncoder[ConstructionPreprocessRequest]
  implicit lazy val ConstructionPreprocessRequestDecoder =
    deriveConfiguredMagnoliaDecoder[ConstructionPreprocessRequest]

  implicit lazy val BlockTransactionResponseEncoder = deriveConfiguredMagnoliaEncoder[BlockTransactionResponse]
  implicit lazy val BlockTransactionResponseDecoder = deriveConfiguredMagnoliaDecoder[BlockTransactionResponse]

  implicit lazy val MempoolTransactionResponseEncoder = deriveConfiguredMagnoliaEncoder[MempoolTransactionResponse]
  implicit lazy val MempoolTransactionResponseDecoder = deriveConfiguredMagnoliaDecoder[MempoolTransactionResponse]

  implicit lazy val BlockEncoder = deriveConfiguredMagnoliaEncoder[Block]
  implicit lazy val BlockDecoder = deriveConfiguredMagnoliaDecoder[Block]
  implicit lazy val ConstructionHashRequestEncoder = deriveConfiguredMagnoliaEncoder[ConstructionHashRequest]
  implicit lazy val ConstructionHashRequestDecoder = deriveConfiguredMagnoliaDecoder[ConstructionHashRequest]

  implicit lazy val ConstructionMetadataResponseMEncoder =
    deriveConfiguredMagnoliaEncoder[ConstructionMetadataResponseMetadata]
  implicit lazy val ConstructionMetadataResponseMDecoder =
    deriveConfiguredMagnoliaDecoder[ConstructionMetadataResponseMetadata]

  implicit lazy val ConstructionMetadataResponseEncoder = deriveConfiguredMagnoliaEncoder[ConstructionMetadataResponse]
  implicit lazy val ConstructionMetadataResponseDecoder = deriveConfiguredMagnoliaDecoder[ConstructionMetadataResponse]

  implicit lazy val ConstructionParseResponseEncoder = deriveConfiguredMagnoliaEncoder[ConstructionParseResponse]
  implicit lazy val ConstructionParseResponseDecoder = deriveConfiguredMagnoliaDecoder[ConstructionParseResponse]
  implicit lazy val TransactionIdentifierResponseEncoder =
    deriveConfiguredMagnoliaEncoder[TransactionIdentifierResponse]
  implicit lazy val TransactionIdentifierResponseDecoder =
    deriveConfiguredMagnoliaDecoder[TransactionIdentifierResponse]
  implicit lazy val BlockResponseEncoder = deriveConfiguredMagnoliaEncoder[BlockResponse]
  implicit lazy val BlockResponseDecoder = deriveConfiguredMagnoliaDecoder[BlockResponse]
  implicit lazy val ConstructionSubmitRequestEncoder = deriveConfiguredMagnoliaEncoder[ConstructionSubmitRequest]
  implicit lazy val ConstructionSubmitRequestDecoder = deriveConfiguredMagnoliaDecoder[ConstructionSubmitRequest]
  implicit lazy val SignatureEncoder = deriveConfiguredMagnoliaEncoder[Signature]
  implicit lazy val SignatureDecoder = deriveConfiguredMagnoliaDecoder[Signature]
  implicit lazy val BlockRequestEncoder = deriveConfiguredMagnoliaEncoder[BlockRequest]
  implicit lazy val BlockRequestDecoder = deriveConfiguredMagnoliaDecoder[BlockRequest]

  implicit lazy val CallResponseAEncoder = deriveConfiguredMagnoliaEncoder[CallResponseActual]
  implicit lazy val CallResponseADecoder = deriveConfiguredMagnoliaDecoder[CallResponseActual]
  implicit lazy val CallResponseEncoder = deriveConfiguredMagnoliaEncoder[CallResponse]
  implicit lazy val CallResponseDecoder = deriveConfiguredMagnoliaDecoder[CallResponse]
  implicit lazy val AccountCoinsRequestEncoder = deriveConfiguredMagnoliaEncoder[AccountCoinsRequest]
  implicit lazy val AccountCoinsRequestDecoder = deriveConfiguredMagnoliaDecoder[AccountCoinsRequest]

  implicit lazy val CoinEncoder = deriveConfiguredMagnoliaEncoder[Coin]
  implicit lazy val CoinDecoder = deriveConfiguredMagnoliaDecoder[Coin]
  implicit lazy val BlockTransactionRequestEncoder = deriveConfiguredMagnoliaEncoder[BlockTransactionRequest]
  implicit lazy val BlockTransactionRequestDecoder = deriveConfiguredMagnoliaDecoder[BlockTransactionRequest]

  implicit lazy val ConstructionCombineRequestEncoder = deriveConfiguredMagnoliaEncoder[ConstructionCombineRequest]
  implicit lazy val ConstructionCombineRequestDecoder = deriveConfiguredMagnoliaDecoder[ConstructionCombineRequest]
  implicit lazy val CallRequestEncoder = deriveConfiguredMagnoliaEncoder[CallRequest]
  implicit lazy val CallRequestDecoder = deriveConfiguredMagnoliaDecoder[CallRequest]

  implicit lazy val ConstructionMetadataRequestEncoder = deriveConfiguredMagnoliaEncoder[ConstructionMetadataRequest]
  implicit lazy val ConstructionMetadataRequestDecoder = deriveConfiguredMagnoliaDecoder[ConstructionMetadataRequest]
  implicit lazy val SyncStatusEncoder = deriveConfiguredMagnoliaEncoder[SyncStatus]
  implicit lazy val SyncStatusDecoder = deriveConfiguredMagnoliaDecoder[SyncStatus]

  implicit lazy val AccountBalanceResponseMEncoder = deriveConfiguredMagnoliaEncoder[AccountBalanceResponseMetadata]
  implicit lazy val AccountBalanceResponseMDecoder = deriveConfiguredMagnoliaDecoder[AccountBalanceResponseMetadata]

  implicit lazy val AccountBalanceResponseEncoder = deriveConfiguredMagnoliaEncoder[AccountBalanceResponse]
  implicit lazy val AccountBalanceResponseDecoder = deriveConfiguredMagnoliaDecoder[AccountBalanceResponse]

  implicit lazy val MempoolTransactionRequestEncoder = deriveConfiguredMagnoliaEncoder[MempoolTransactionRequest]
  implicit lazy val MempoolTransactionRequestDecoder = deriveConfiguredMagnoliaDecoder[MempoolTransactionRequest]
  implicit lazy val BlockEventEncoder = deriveConfiguredMagnoliaEncoder[BlockEvent]
  implicit lazy val BlockEventDecoder = deriveConfiguredMagnoliaDecoder[BlockEvent]
  implicit lazy val EventsBlocksRequestEncoder = deriveConfiguredMagnoliaEncoder[EventsBlocksRequest]
  implicit lazy val EventsBlocksRequestDecoder = deriveConfiguredMagnoliaDecoder[EventsBlocksRequest]
  implicit lazy val SearchTransactionsResponseEncoder = deriveConfiguredMagnoliaEncoder[SearchTransactionsResponse]
  implicit lazy val SearchTransactionsResponseDecoder = deriveConfiguredMagnoliaDecoder[SearchTransactionsResponse]

  implicit lazy val NetworkListResponseEncoder = deriveConfiguredMagnoliaEncoder[NetworkListResponse]
  implicit lazy val NetworkListResponseDecoder = deriveConfiguredMagnoliaDecoder[NetworkListResponse]
  implicit lazy val ConstructionCombineResponseEncoder = deriveConfiguredMagnoliaEncoder[ConstructionCombineResponse]
  implicit lazy val ConstructionCombineResponseDecoder = deriveConfiguredMagnoliaDecoder[ConstructionCombineResponse]

  implicit lazy val ConstructionPayloadsResponseEncoder = deriveConfiguredMagnoliaEncoder[ConstructionPayloadsResponse]
  implicit lazy val ConstructionPayloadsResponseDecoder = deriveConfiguredMagnoliaDecoder[ConstructionPayloadsResponse]

  implicit lazy val ConstructionDeriveRequestEncoder = deriveConfiguredMagnoliaEncoder[ConstructionDeriveRequest]
  implicit lazy val ConstructionDeriveRequestDecoder = deriveConfiguredMagnoliaDecoder[ConstructionDeriveRequest]

  implicit lazy val AccountCoinsResponseMEncoder = deriveConfiguredMagnoliaEncoder[AccountCoinsResponseMetadata]
  implicit lazy val AccountCoinsResponseMDecoder = deriveConfiguredMagnoliaDecoder[AccountCoinsResponseMetadata]
  implicit lazy val AccountCoinsResponseEncoder = deriveConfiguredMagnoliaEncoder[AccountCoinsResponse]
  implicit lazy val AccountCoinsResponseDecoder = deriveConfiguredMagnoliaDecoder[AccountCoinsResponse]

  implicit lazy val PeerEncoder = deriveConfiguredMagnoliaEncoder[Peer]
  implicit lazy val PeerDecoder = deriveConfiguredMagnoliaDecoder[Peer]

  implicit lazy val NetworkStatusResponseEncoder = deriveConfiguredMagnoliaEncoder[NetworkStatusResponse]
  implicit lazy val NetworkStatusResponseDecoder = deriveConfiguredMagnoliaDecoder[NetworkStatusResponse]

  implicit lazy val NetworkRequestEncoder = deriveConfiguredMagnoliaEncoder[NetworkRequest]
  implicit lazy val NetworkRequestDecoder = deriveConfiguredMagnoliaDecoder[NetworkRequest]
  implicit lazy val EventsBlocksResponseEncoder = deriveConfiguredMagnoliaEncoder[EventsBlocksResponse]
  implicit lazy val EventsBlocksResponseDecoder = deriveConfiguredMagnoliaDecoder[EventsBlocksResponse]

  implicit lazy val ConstructionPreprocessResponseEncoder =
    deriveConfiguredMagnoliaEncoder[ConstructionPreprocessResponse]
  implicit lazy val ConstructionPreprocessResponseDecoder =
    deriveConfiguredMagnoliaDecoder[ConstructionPreprocessResponse]

}
