package org.tesselation.domain.cluster.storage

import org.tesselation.schema.node.NodeState

trait NodeStorage[F[_]] {
  def getNodeState: F[NodeState]
  def setNodeState(nodeState: NodeState): F[Unit]
  def canJoinCluster: F[Boolean]
}
