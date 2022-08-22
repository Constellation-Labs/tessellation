package org.tessellation.sdk.domain.node

import org.tessellation.schema.node.{NodeState, NodeStateTransition}

import fs2.Stream

trait NodeStorage[F[_]] {
  def getNodeState: F[NodeState]

  def setNodeState(nodeState: NodeState): F[Unit]

  def tryModifyState[A](from: Set[NodeState], onStart: NodeState, onFinish: NodeState)(fn: => F[A]): F[A]

  def tryModifyState(from: Set[NodeState], to: NodeState): F[Unit]

  def tryModifyState[A](from: NodeState, onStart: NodeState, onFinish: NodeState)(fn: => F[A]): F[A] =
    tryModifyState(Set(from), onStart, onFinish)(fn)

  def tryModifyState(from: NodeState, to: NodeState): F[Unit] =
    tryModifyState(Set(from), to)

  def tryModifyStateGetResult(from: NodeState, to: NodeState): F[NodeStateTransition] =
    tryModifyStateGetResult(Set(from), to)

  def tryModifyStateGetResult(from: Set[NodeState], to: NodeState): F[NodeStateTransition]

  def canJoinCluster: F[Boolean]

  def nodeStates: Stream[F, NodeState]
}
