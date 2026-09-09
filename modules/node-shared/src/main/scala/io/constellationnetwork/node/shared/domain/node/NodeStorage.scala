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

  /** Flag set by AbandonmentTracker before triggering recovery download. When true, DownloadDaemon uses the layer's `recoveryDownload`
    * implementation rather than full `download`. dag-l0 resyncs MptStore from the downloaded checkpoint; currency-l0 currently delegates to
    * its full download. Cleared after download completes.
    */
  def setRecoveryDownload: F[Unit]

  def clearRecoveryDownload: F[Unit]

  def isRecoveryDownload: F[Boolean]

  /** Select the bounded forward-only follower catch-up path for the next download. Layers that do not implement a specialized path fall
    * back to their ordinary recovery download.
    */
  def setFollowerCatchUpDownload: F[Unit]

  def getDownloadMode: F[DownloadMode]

  /** When true, the node must have ≥2 facilitators to complete a consensus round. Set by RunValidator at startup — validators must never
    * produce solo snapshots because solo production from multiple validators creates divergent forks. RunRollback/RunGenesis nodes leave
    * this false so they can bootstrap solo.
    */
  def setValidatorMode: F[Unit]

  def isValidatorMode: F[Boolean]

}

sealed trait DownloadMode
object DownloadMode {
  case object Full extends DownloadMode
  case object Recovery extends DownloadMode
  case object FollowerCatchUp extends DownloadMode
}
