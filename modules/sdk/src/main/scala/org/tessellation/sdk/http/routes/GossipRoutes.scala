package org.tessellation.sdk.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.gossip.RumorStorage
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.signature.Signed

import fs2.Stream
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl._
import org.http4s.server.Router

final case class GossipRoutes[F[_]: Async: KryoSerializer: Metrics](
  rumorStorage: RumorStorage[F],
  gossip: Gossip[F]
) extends Http4sDsl[F] {

  private val prefixPath = "/rumors"
  private val chunkSize = 10

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "peer" / "query" =>
      for {
        inquiryRequest <- req.as[PeerRumorInquiryRequest]
        inquiryOrdinals = inquiryRequest.ordinals
        localPeerIds <- rumorStorage.getPeerIds
        additionalOrdinals = localPeerIds.diff(inquiryOrdinals.keySet).toList.map(_ -> Ordinal.MinValue)
        result <- Ok(peerRumorStream(inquiryOrdinals.toList) ++ peerRumorStream(additionalOrdinals))
      } yield result

    case POST -> Root / "peer" / "init" =>
      for {
        lastRumors <- rumorStorage.getLastPeerRumors
        result <- Ok(Stream.emits(lastRumors.toList).covary[F]) // Stream.fromIterator fails because of a double pull from the stream
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
        rumors = Stream.eval(rumorStorage.getCommonRumors(queryRequest.query)).flatMap(Stream.fromIterator(_, chunkSize))
        result <- Ok(rumors)
      } yield result

    case GET -> Root / "common" / "init" =>
      for {
        seen <- rumorStorage.getCommonRumorSeenHashes
        result <- Ok(CommonRumorInitResponse(seen))
      } yield result
  }

  private def peerRumorStream(ordinals: List[(PeerId, Ordinal)]): Stream[F, Signed[PeerRumorRaw]] =
    Stream
      .emits(ordinals)
      .evalMap {
        case (peerId, ordinal) => rumorStorage.getPeerRumorsFromCursor(peerId, ordinal)
      }
      .flatMap(Stream.fromIterator(_, chunkSize))

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
