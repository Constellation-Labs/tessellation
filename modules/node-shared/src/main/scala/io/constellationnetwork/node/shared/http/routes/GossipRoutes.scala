package io.constellationnetwork.node.shared.http.routes

import cats.data.Chain
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.GossipTimeoutsConfig
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.{Chunk, Stream}
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl._
import org.http4s.server.middleware.Timeout

final case class GossipRoutes[F[_]: Async](
  rumorStorage: RumorStorage[F],
  gossip: Gossip[F],
  gossipTimeoutsConfig: GossipTimeoutsConfig
) extends Http4sDsl[F]
    with P2PRoutes[F] {

  protected val prefixPath: InternalUrlPrefix = "/rumors"

  protected val p2p: HttpRoutes[F] = Timeout(gossipTimeoutsConfig.routes)(HttpRoutes.of[F] {
    case req @ POST -> Root / "peer" / "query" =>
      for {
        inquiryRequest <- req.as[PeerRumorInquiryRequest]
        inquiryOrdinals = inquiryRequest.ordinals
        localPeerIds <- rumorStorage.getPeerIds
        rumors <- peerRumorChain(inquiryOrdinals.toList)
        additionalOrdinals = localPeerIds.diff(inquiryOrdinals.keySet).toList.map(_ -> Ordinal.MinValue)
        additionalRumors <- peerRumorChain(additionalOrdinals)
        result <- Ok(streamFromChain(rumors ++ additionalRumors))
      } yield result

    case POST -> Root / "peer" / "init" =>
      for {
        rumors <- rumorStorage.getLastPeerRumors
        result <- Ok(streamFromChain(rumors))
      } yield result

    case GET -> Root / "common" / "offer" =>
      for {
        offer <- rumorStorage.getCommonRumorActiveHashes
        response = CommonRumorOfferResponse(offer)
        result <- Ok(response)
      } yield result

    case req @ POST -> Root / "common" / "query" =>
      for {
        queryRequest <- req.as[QueryCommonRumorsRequest]
        rumors <- rumorStorage.getCommonRumors(queryRequest.query)
        result <- Ok(streamFromChain(rumors))
      } yield result

    case GET -> Root / "common" / "init" =>
      for {
        seen <- rumorStorage.getCommonRumorSeenHashes
        result <- Ok(CommonRumorInitResponse(seen))
      } yield result
  })

  private def peerRumorChain(ordinals: List[(PeerId, Ordinal)]): F[Chain[Signed[PeerRumorRaw]]] =
    Chain
      .fromSeq(ordinals)
      .flatTraverse {
        case (peerId, ordinal) => rumorStorage.getPeerRumorsFromCursor(peerId, ordinal)
      }

  private def streamFromChain[A](chain: Chain[A]): Stream[F, A] =
    Stream.chunk(Chunk.chain(chain)).covary[F]

}
