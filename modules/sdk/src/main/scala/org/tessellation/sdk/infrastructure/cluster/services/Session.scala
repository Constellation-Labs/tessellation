package org.tessellation.sdk.infrastructure.cluster.services

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.functor._

import org.tessellation.schema.cluster._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.sdk.domain.node.NodeStorage

import com.comcast.ip4s.Host

object Session {

  def make[F[+ _]: Async](
    sessionStorage: SessionStorage[F],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F]
  ): Session[F] =
    new Session[F] {

      private val validNodeStatesForSessionCreation: Set[NodeState] = Set(NodeState.GenesisReady, NodeState.ReadyToJoin)

      def createSession: F[SessionToken] =
        nodeStorage
          .tryModify(
            validNodeStatesForSessionCreation,
            NodeState.StartingSession,
            NodeState.SessionStarted
          ) {
            sessionStorage.createToken
          }

      private def verifyToken(
        headerToken: Option[SessionToken],
        peers: F[IterableOnce[Peer]]
      ): F[TokenVerificationResult] =
        headerToken.fold[F[TokenVerificationResult]](EmptyHeaderToken.pure[F]) { token =>
          peers.map(
            _.iterator.find(_.session == token).fold[TokenVerificationResult](TokenDoesntMatch)(_ => TokenValid)
          )
        }

      def verifyToken(peer: PeerId, headerToken: Option[SessionToken]): F[TokenVerificationResult] =
        verifyToken(headerToken, clusterStorage.getPeer(peer))

      def verifyToken(host: Host, headerToken: Option[SessionToken]): F[TokenVerificationResult] =
        verifyToken(headerToken, clusterStorage.getPeers(host))
    }
}
