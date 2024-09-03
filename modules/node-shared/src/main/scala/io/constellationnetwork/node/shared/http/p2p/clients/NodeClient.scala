package io.constellationnetwork.node.shared.http.p2p.clients

import cats.effect.Async

import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.schema.cluster.SessionToken
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.security.SecurityProvider

import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

trait NodeClient[F[_]] {
  def getState: PeerResponse[F, NodeState]
  def health: PeerResponse[F, Boolean]
  def getSession: PeerResponse[F, Option[SessionToken]]
}

object NodeClient {

  def make[F[_]: Async: SecurityProvider](client: Client[F], session: Session[F]): NodeClient[F] =
    new NodeClient[F] with Http4sClientDsl[F] {

      def getState: PeerResponse[F, NodeState] =
        PeerResponse[F, NodeState]("node/state")(client, session)

      def health: PeerResponse[F, Boolean] =
        PeerResponse.successful("node/health")(client, session)

      def getSession: PeerResponse[F, Option[SessionToken]] =
        PeerResponse[F, Option[SessionToken]]("node/session")(client, session)
    }
}
