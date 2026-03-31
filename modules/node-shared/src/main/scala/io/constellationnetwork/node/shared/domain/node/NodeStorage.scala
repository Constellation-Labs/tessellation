package io.constellationnetwork.node.shared.domain.node

import io.constellationnetwork.schema.node.{NodeState, NodeStateTransition}

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

  def setJoiningGracePeriod: F[Unit]

  def clearJoiningGracePeriod: F[Unit]

  def decrementJoiningGracePeriod: F[Unit]

  def isInJoiningGracePeriod: F[Boolean]

  /** Flag set by AbandonmentTracker before triggering recovery download. When true, DownloadDaemon uses the incremental recoveryDownload
    * path (skips cache clearing and observe phase). Cleared after download completes.
    */
  def setRecoveryDownload: F[Unit]

  def clearRecoveryDownload: F[Unit]

  def isRecoveryDownload: F[Boolean]

}
