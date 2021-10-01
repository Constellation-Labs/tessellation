package org.tesselation.infrastructure.node

import cats.Monad
import cats.effect.Ref
import cats.syntax.functor._

import org.tesselation.domain.cluster.storage.NodeStorage
import org.tesselation.schema.node.NodeState

object NodeStorage {

  def make[F[_]: Monad: Ref.Make]: F[NodeStorage[F]] =
    Ref.of[F, NodeState](NodeState.Initial).map(make(_))

  def make[F[_]: Monad](nodeState: Ref[F, NodeState]): NodeStorage[F] = new NodeStorage[F] {
    def getNodeState: F[NodeState] = nodeState.get

    def setNodeState(state: NodeState): F[Unit] =
      nodeState.set(state)

    def canJoinCluster: F[Boolean] = nodeState.get.map(_ == NodeState.Initial)
  }
}
