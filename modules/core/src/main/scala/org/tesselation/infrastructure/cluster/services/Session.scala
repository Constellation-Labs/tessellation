package org.tesselation.infrastructure.cluster.services

import cats.effect.Async

import org.tesselation.domain.cluster.services.Session
import org.tesselation.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tesselation.domain.node.NodeStorage
import org.tesselation.schema.cluster.{SessionToken, TokenVerificationResult}
import org.tesselation.schema.node.NodeState
import org.tesselation.schema.peer.PeerId

object Session {

  def make[F[_]: Async](
    sessionStorage: SessionStorage[F],
    clusterStorage: ClusterStorage[F],
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
