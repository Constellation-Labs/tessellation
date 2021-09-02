package org.tesselation.domain.cluster

import org.tesselation.schema.node.NodeState

trait NodeStorage[F[_]] {
  def getNodeState: F[NodeState]
  def canJoinCluster: F[Boolean]
}
