package org.tessellation.sdk.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.generation.Generation
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

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "peer" / "query" =>
      for {
        inquiryRequest <- req.as[PeerRumorInquiryRequest]
        localCounters <- rumorStorage.getPeerRumorHeadCounters
        remoteCounters = inquiryRequest.headCounters
        higherCounters = localCounters.toList.mapFilter {
          case (peerIdAndGen, localCounter) =>
            remoteCounters.get(peerIdAndGen) match {
              case Some(remoteCounter) =>
                if (localCounter > remoteCounter)
                  (peerIdAndGen, remoteCounter.next).some
                else
                  none
              case None =>
                (peerIdAndGen, Counter.MinValue).some
            }
        }
        result <- Ok(peerRumorStream(higherCounters))
      } yield result

    case POST -> Root / "peer" / "init" =>
      for {
        localCounters <- rumorStorage.getPeerRumorHeadCounters
        result <- Ok(peerRumorStream(localCounters.toList))
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
        rumors = Stream.eval(rumorStorage.getCommonRumors(queryRequest.query)).flatMap(Stream.fromIterator(_, 5))
        result <- Ok(rumors)
      } yield result

    case GET -> Root / "common" / "init" =>
      for {
        seen <- rumorStorage.getCommonRumorSeenHashes
        result <- Ok(CommonRumorInitResponse(seen))
      } yield result
  }

  private def peerRumorStream(counters: List[((PeerId, Generation), Counter)]): Stream[F, Signed[PeerRumorRaw]] =
    Stream
      .emits(counters)
      .evalMap {
        case (peerIdAndGen, counter) => rumorStorage.getPeerRumors(peerIdAndGen)(counter)
      }
      .flatMap(Stream.fromIterator(_, 5))

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
