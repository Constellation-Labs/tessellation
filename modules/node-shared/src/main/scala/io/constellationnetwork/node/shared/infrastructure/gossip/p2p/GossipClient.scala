package io.constellationnetwork.node.shared.infrastructure.gossip.p2p

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.config.types.GossipTimeoutsConfig
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.middlewares.TimeoutMiddleware.withResponseBodyTimeout
import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.security.signature.Signed

import fs2.Stream
import io.circe.Json
import io.circe.jawn.CirceSupportParser
import org.http4s.Method._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.{Client, UnexpectedStatus}
import org.typelevel.jawn.Facade
import org.typelevel.jawn.fs2._

trait GossipClient[F[_]] {

  def queryPeerRumors(request: PeerRumorInquiryRequest): PeerResponse[Stream[F, *], Signed[PeerRumorRaw]]

  def getInitialPeerRumors: PeerResponse[Stream[F, *], Signed[PeerRumorRaw]]

  def getCommonRumorOffer: PeerResponse[F, CommonRumorOfferResponse]

  def queryCommonRumors(request: QueryCommonRumorsRequest): PeerResponse[Stream[F, *], Signed[CommonRumorRaw]]

  def getInitialCommonRumorHashes: PeerResponse[F, CommonRumorInitResponse]

}

object GossipClient {
  def make[F[_]: Async](
    client: Client[F],
    session: Session[F],
    gossipTimeoutsConfig: GossipTimeoutsConfig
  ): GossipClient[F] = {
    val timeoutClient: Client[F] =
      withResponseBodyTimeout(client, gossipTimeoutsConfig.client, gossipTimeoutsConfig.response)

    new GossipClient[F] {
      implicit val facade: Facade[Json] = new CirceSupportParser(None, false).facade

      def queryPeerRumors(request: PeerRumorInquiryRequest): PeerResponse[Stream[F, *], Signed[PeerRumorRaw]] =
        PeerResponse("rumors/peer/query", POST)(timeoutClient, session) { (req, c) =>
          c.stream(req.withEntity(request)).flatMap { resp =>
            if (resp.status.isSuccess)
              resp.body.chunks.parseJsonStream[Json].evalMap(_.as[Signed[PeerRumorRaw]].liftTo[F])
            else
              Stream.raiseError[F](UnexpectedStatus(resp.status, req.method, req.uri))
          }
        }

      def getInitialPeerRumors: PeerResponse[Stream[F, *], Signed[PeerRumorRaw]] =
        PeerResponse("rumors/peer/init", POST)(timeoutClient, session) { (req, c) =>
          c.stream(req.withEmptyBody).flatMap { resp =>
            if (resp.status.isSuccess)
              resp.body.chunks.parseJsonStream[Json].evalMap(_.as[Signed[PeerRumorRaw]].liftTo[F])
            else
              Stream.raiseError[F](UnexpectedStatus(resp.status, req.method, req.uri))
          }
        }

      def getCommonRumorOffer: PeerResponse[F, CommonRumorOfferResponse] =
        PeerResponse("rumors/common/offer", GET)(timeoutClient, session) { (req, c) =>
          c.expect[CommonRumorOfferResponse](req.withEmptyBody)
        }

      def queryCommonRumors(request: QueryCommonRumorsRequest): PeerResponse[Stream[F, *], Signed[CommonRumorRaw]] =
        PeerResponse("rumors/common/query", POST)(timeoutClient, session) { (req, c) =>
          c.stream(req.withEntity(request)).flatMap { resp =>
            if (resp.status.isSuccess)
              resp.body.chunks.parseJsonStream[Json].evalMap(_.as[Signed[CommonRumorRaw]].liftTo[F])
            else
              Stream.raiseError[F](UnexpectedStatus(resp.status, req.method, req.uri))
          }
        }

      def getInitialCommonRumorHashes: PeerResponse[F, CommonRumorInitResponse] =
        PeerResponse("rumors/common/init", GET)(timeoutClient, session) { (req, c) =>
          c.expect[CommonRumorInitResponse](req.withEmptyBody)
        }
    }
  }
}
