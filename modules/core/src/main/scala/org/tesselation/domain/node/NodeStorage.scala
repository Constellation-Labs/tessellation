package org.tesselation.domain.node

import cats.Applicative

import org.tesselation.schema.node.NodeState

trait NodeStorage[F[_]] {
  def getNodeState: F[NodeState]

  def setNodeState(nodeState: NodeState): F[Unit]

  def tryModifyState[A](from: Set[NodeState], onStart: NodeState, onFinish: NodeState)(fn: => F[A]): F[A]

  def tryModifyState[A](from: NodeState, onStart: NodeState, onFinish: NodeState)(fn: => F[A]): F[A] =
    tryModifyState(Set(from), onStart, onFinish)(fn)

  def tryModifyState(from: Set[NodeState], to: NodeState)(implicit F: Applicative[F]): F[Unit] =
    tryModifyState(from, onStart = to, onFinish = to)(F.unit)

  def tryModifyState(from: NodeState, to: NodeState)(implicit F: Applicative[F]): F[Unit] =
    tryModifyState(Set(from), to)

  def canJoinCluster: F[Boolean]
}
