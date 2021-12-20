package org.tessellation.infrastructure.cluster.services

import cats.effect.Async

import org.tessellation.domain.cluster.services.Session
import org.tessellation.schema.cluster.{SessionToken, TokenVerificationResult}
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.storage.SessionStorage
import org.tessellation.sdk.domain.node.NodeStorage

object Session {

  def make[F[_]: Async](
    sessionStorage: SessionStorage[F],
    nodeStorage: NodeStorage[F]
  ): Session[F] =
    new Session[F] {

      private val validNodeStatesForSessionCreation: Set[NodeState] = Set(NodeState.GenesisReady, NodeState.ReadyToJoin)

      def createSession: F[SessionToken] =
        nodeStorage
          .tryModifyState(
            validNodeStatesForSessionCreation,
            NodeState.StartingSession,
            NodeState.SessionStarted
          ) {
            sessionStorage.createToken
          }

      def verifyToken(peer: PeerId, headerToken: Option[SessionToken]): F[TokenVerificationResult] = ???
    }
}
