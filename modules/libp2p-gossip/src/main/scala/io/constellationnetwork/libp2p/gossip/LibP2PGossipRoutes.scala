package io.constellationnetwork.libp2p.gossip

import cats.data.Chain
import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.Signed

import io.circe.{Decoder, Encoder, Json}
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class LibP2PGossipRoutes[F[_]: Async] private (
  client: LibP2PGossipClient[F],
  server: LibP2PGossipServer[F],
  config: LibP2PGossipRoutes.Config
) {
  private val logger = Slf4jLogger.getLogger[F]

  // Endpoint: POST /rumors/peer/query
  def queryPeerRumors(request: PeerRumorInquiryRequest): F[Chain[Signed[PeerRumorRaw]]] =
    for {
      _ <- logger.debug(s"Querying peer rumors for ordinals: ${request.ordinals}")
      rumors <- client.queryPeerRumors(request.ordinals)
      _ <- logger.debug(s"Retrieved ${rumors.size} peer rumors")
    } yield rumors

  // Endpoint: POST /rumors/peer/init
  def getInitialPeerRumors: F[Chain[Signed[PeerRumorRaw]]] =
    for {
      _ <- logger.debug("Getting initial peer rumors")
      rumors <- client.getInitialPeerRumors
      _ <- logger.debug(s"Retrieved ${rumors.size} initial peer rumors")
    } yield rumors

  // Endpoint: GET /rumors/common/offer
  def getCommonRumorOffer: F[CommonRumorOfferResponse] =
    for {
      _ <- logger.debug("Getting common rumor offer")
      hashes <- client.getCommonRumorOffer
      response = CommonRumorOfferResponse(hashes)
      _ <- logger.debug(s"Retrieved ${hashes.size} common rumor hashes")
    } yield response

  // Endpoint: POST /rumors/common/query
  def queryCommonRumors(request: QueryCommonRumorsRequest): F[Chain[Signed[CommonRumorRaw]]] =
    for {
      _ <- logger.debug(s"Querying common rumors for hashes: ${request.query}")
      rumors <- client.queryCommonRumors(request.query)
      _ <- logger.debug(s"Retrieved ${rumors.size} common rumors")
    } yield rumors

  // Endpoint: GET /rumors/common/init
  def getInitialCommonRumorHashes: F[CommonRumorInitResponse] =
    for {
      _ <- logger.debug("Getting initial common rumor hashes")
      hashes <- client.getInitialCommonRumorHashes
      response = CommonRumorInitResponse(hashes)
      _ <- logger.debug(s"Retrieved ${hashes.size} initial common rumor hashes")
    } yield response

  // Register handlers for incoming requests
  def registerRequestHandlers: F[Unit] =
    for {
      _ <- server.registerRequestHandler[PeerRumorInquiryRequest, Chain[Signed[PeerRumorRaw]]](
        "peer_rumor_query",
        queryPeerRumors
      )
      _ <- server.registerRequestHandler[Unit, Chain[Signed[PeerRumorRaw]]](
        "peer_rumor_init",
        _ => getInitialPeerRumors
      )
      _ <- server.registerRequestHandler[Unit, CommonRumorOfferResponse](
        "common_rumor_offer",
        _ => getCommonRumorOffer
      )
      _ <- server.registerRequestHandler[QueryCommonRumorsRequest, Chain[Signed[CommonRumorRaw]]](
        "common_rumor_query",
        queryCommonRumors
      )
      _ <- server.registerRequestHandler[Unit, CommonRumorInitResponse](
        "common_rumor_init",
        _ => getInitialCommonRumorHashes
      )
      _ <- logger.info("Registered all request handlers")
    } yield ()

  // Handle incoming rumors
  def handleIncomingRumor(rumor: Signed[RumorRaw]): F[Unit] =
    server.handleIncomingRumor(rumor)

  // Connect to a peer
  def connectToPeer(address: String): F[Unit] =
    client.connectToPeer(address)

  // Get local peer information
  def getLocalPeerId: F[String] = client.getLocalPeerId

  def getListenAddresses: F[List[String]] = client.getListenAddresses
}

object LibP2PGossipRoutes {

  final case class Config(
    requestTimeout: FiniteDuration = 30.seconds,
    maxRetries: Int = 3,
    retryDelay: FiniteDuration = 1.second
  )

  def make[F[_]: Async](
    client: LibP2PGossipClient[F],
    server: LibP2PGossipServer[F],
    config: Config
  ): F[LibP2PGossipRoutes[F]] =
    for {
      routes <- Async[F].delay(new LibP2PGossipRoutes[F](client, server, config))
      _ <- routes.registerRequestHandlers
    } yield routes
}
