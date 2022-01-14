package org.tessellation.sdk.infrastructure.gossip.p2p

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse

import org.http4s.Method._
import org.http4s.client.Client

trait GossipClient[F[_]] {

  def startGossiping(request: StartGossipRoundRequest): PeerResponse[F, StartGossipRoundResponse]

  def endGossiping(request: EndGossipRoundRequest): PeerResponse[F, EndGossipRoundResponse]

}

object GossipClient {

  def make[F[_]: Async: KryoSerializer](client: Client[F], session: Session[F]): GossipClient[F] =
    new GossipClient[F] {

      def startGossiping(request: StartGossipRoundRequest): PeerResponse[F, StartGossipRoundResponse] =
        PeerResponse[F, StartGossipRoundResponse]("gossip/start", POST)(client, session) { (req, c) =>
          c.expect[StartGossipRoundResponse](req.withEntity(request))
        }

      def endGossiping(request: EndGossipRoundRequest): PeerResponse[F, EndGossipRoundResponse] =
        PeerResponse[F, EndGossipRoundResponse]("gossip/end", POST)(client, session) { (req, c) =>
          c.expect[EndGossipRoundResponse](req.withEntity(request))
        }
    }
}
