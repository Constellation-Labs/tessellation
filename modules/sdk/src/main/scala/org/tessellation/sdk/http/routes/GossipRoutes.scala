package org.tessellation.sdk.http.routes

import cats.data.Chain
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.signature.Signed
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.metrics.Metrics

import fs2.{Chunk, Stream}
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl._
import org.http4s.server.Router

final case class GossipRoutes[F[_]: Async: KryoSerializer: Metrics](
  rumorStorage: RumorStorage[F],
  gossip: Gossip[F]
) extends Http4sDsl[F] {

  private val prefixPath = "/rumors"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
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
  }

  private def peerRumorChain(ordinals: List[(PeerId, Ordinal)]): F[Chain[Signed[PeerRumorRaw]]] =
    Chain
      .fromSeq(ordinals)
      .flatTraverse {
        case (peerId, ordinal) => rumorStorage.getPeerRumorsFromCursor(peerId, ordinal)
      }

  private def streamFromChain[A](chain: Chain[A]): Stream[F, A] =
    Stream.chunk(Chunk.chain(chain)).covary[F]

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
