package org.tessellation.node.shared.infrastructure.cluster.services

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.functor._

import org.tessellation.node.shared.domain.cluster.services.Session
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.schema.cluster._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{Peer, PeerId}

object Session {

  def make[F[+_]: Async](
    sessionStorage: SessionStorage[F],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F]
  ): Session[F] =
    new Session[F] {

      private val validNodeStatesForSessionCreation: Set[NodeState] =
        Set(NodeState.GenesisReady, NodeState.RollbackDone, NodeState.ReadyToJoin)

      def createSession: F[SessionToken] =
        nodeStorage
          .tryModifyState(
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
    }
}
