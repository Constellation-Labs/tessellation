package org.tessellation.sdk.infrastructure.gossip.p2p

import cats.effect.Async
import cats.syntax.all._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.signature.Signed

import fs2.Stream
import io.circe.Json
import io.circe.jawn.CirceSupportParser
import org.http4s.Method._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client
import org.typelevel.jawn.Facade
import org.typelevel.jawn.fs2._

trait GossipClient[F[_]] {

  def getOffer: PeerResponse[F, RumorOfferResponse]

  def sendInquiry(request: RumorInquiryRequest): PeerResponse[Stream[F, *], Signed[RumorRaw]]

}

object GossipClient {

  def make[F[_]: Async: KryoSerializer](client: Client[F], session: Session[F]): GossipClient[F] =
    new GossipClient[F] {

      implicit val facade: Facade[Json] = new CirceSupportParser(None, false).facade

      def getOffer: PeerResponse[F, RumorOfferResponse] =
        PeerResponse("gossip/offer", GET)(client, session) { (req, c) =>
          c.expect[RumorOfferResponse](req.withEmptyBody)
        }
      def sendInquiry(request: RumorInquiryRequest): PeerResponse[Stream[F, *], Signed[RumorRaw]] =
        PeerResponse(s"gossip/inquiry", POST)(client, session) { (req, c) =>
          c.stream(req.withEntity(request)).flatMap { resp =>
            resp.body.chunks.parseJsonStream[Json].evalMap(_.as[Signed[RumorRaw]].liftTo[F])
          }
        }
    }
}
