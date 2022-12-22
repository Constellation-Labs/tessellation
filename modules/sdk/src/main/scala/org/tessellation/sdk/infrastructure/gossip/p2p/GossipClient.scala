package org.tessellation.sdk.infrastructure.gossip.p2p

import cats.effect.Async
import cats.syntax.all._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.schema.security.signature.Signed
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse

import fs2.Stream
import io.circe.Json
import io.circe.jawn.CirceSupportParser
import org.http4s.Method._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client
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

  def make[F[_]: Async: KryoSerializer](client: Client[F], session: Session[F]): GossipClient[F] =
    new GossipClient[F] {

      implicit val facade: Facade[Json] = new CirceSupportParser(None, false).facade

      def queryPeerRumors(request: PeerRumorInquiryRequest): PeerResponse[Stream[F, *], Signed[PeerRumorRaw]] =
        PeerResponse("rumors/peer/query", POST)(client, session) { (req, c) =>
          c.stream(req.withEntity(request)).flatMap { resp =>
            resp.body.chunks.parseJsonStream[Json].evalMap(_.as[Signed[PeerRumorRaw]].liftTo[F])
          }
        }

      def getInitialPeerRumors: PeerResponse[Stream[F, *], Signed[PeerRumorRaw]] =
        PeerResponse("rumors/peer/init", POST)(client, session) { (req, c) =>
          c.stream(req.withEmptyBody).flatMap { resp =>
            resp.body.chunks.parseJsonStream[Json].evalMap(_.as[Signed[PeerRumorRaw]].liftTo[F])
          }
        }

      def getCommonRumorOffer: PeerResponse[F, CommonRumorOfferResponse] =
        PeerResponse("rumors/common/offer", GET)(client, session) { (req, c) =>
          c.expect[CommonRumorOfferResponse](req.withEmptyBody)
        }

      def queryCommonRumors(request: QueryCommonRumorsRequest): PeerResponse[Stream[F, *], Signed[CommonRumorRaw]] =
        PeerResponse("rumors/common/query", POST)(client, session) { (req, c) =>
          c.stream(req.withEntity(request)).flatMap { resp =>
            resp.body.chunks.parseJsonStream[Json].evalMap(_.as[Signed[CommonRumorRaw]].liftTo[F])
          }
        }

      def getInitialCommonRumorHashes: PeerResponse[F, CommonRumorInitResponse] =
        PeerResponse("rumors/common/init", GET)(client, session) { (req, c) =>
          c.expect[CommonRumorInitResponse](req.withEmptyBody)
        }
    }
}
