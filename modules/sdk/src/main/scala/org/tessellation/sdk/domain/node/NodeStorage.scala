package org.tessellation.sdk.domain.node

import org.tessellation.fsm.FSM
import org.tessellation.schema.node.NodeState

import fs2.Stream

trait NodeStorage[F[_]] extends FSM[F, NodeState] {
  def canJoinCluster: F[Boolean]

  def nodeStates: Stream[F, NodeState]
}
