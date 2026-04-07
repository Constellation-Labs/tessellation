package io.constellationnetwork.node.shared.infrastructure.node

import cats.effect.{Concurrent, Ref}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.schema.node.{InvalidNodeStateTransition, NodeState, NodeStateTransition}

import fs2.Stream
import fs2.concurrent.Topic

object NodeStorage {

  private val maxQueuedNodeStates = 1000

  /** Number of consensus rounds to keep joining grace period active after download. During grace, facilitatorsHash fork checks are
    * suppressed because PeerQualityTracker scores haven't converged yet — different nodes exclude different peers, causing false-positive
    * FORK_DETECTED that evicts freshly-joined nodes.
    */
  private val joiningGraceRounds = 3

  def make[F[_]: Concurrent: Ref.Make]: F[NodeStorage[F]] =
    for {
      stateRef <- Ref.of[F, NodeState](NodeState.Initial)
      stateTopic <- Topic[F, NodeState]
      graceRef <- Ref.of[F, Int](joiningGraceRounds)
      recoveryRef <- Ref.of[F, Boolean](false)
      validatorRef <- Ref.of[F, Boolean](false)
      _ <- stateTopic.publish1(NodeState.Initial)
    } yield make(stateRef, stateTopic, graceRef, recoveryRef, validatorRef)

  def make[F[_]: Concurrent](
    nodeState: Ref[F, NodeState],
    nodeStateTopic: Topic[F, NodeState],
    joiningGracePeriod: Ref[F, Int],
    recoveryDownloadRef: Ref[F, Boolean],
    validatorModeRef: Ref[F, Boolean]
  ): NodeStorage[F] =
    new NodeStorage[F] {
      def getNodeState: F[NodeState] = nodeState.get

      def setNodeState(state: NodeState): F[Unit] =
        nodeState.set(state) >> nodeStateTopic.publish1(state).void

      def canJoinCluster: F[Boolean] = nodeState.get.map(_ == NodeState.ReadyToJoin)

      def tryModifyState[A](from: Set[NodeState], onStart: NodeState, onFinish: NodeState)(fn: => F[A]): F[A] =
        getNodeState.flatMap { initial =>
          modify(from, onStart).flatMap {
            case NodeStateTransition.Failure => InvalidNodeStateTransition(initial, from, onStart).raiseError[F, A]
            case NodeStateTransition.Success =>
              fn.flatMap { res =>
                modify(Set(onStart), onFinish).flatMap {
                  case NodeStateTransition.Failure =>
                    getNodeState >>= { InvalidNodeStateTransition(_, Set(onStart), onFinish).raiseError[F, A] }
                  case NodeStateTransition.Success => Applicative[F].pure(res)
                }
              }.handleErrorWith { error =>
                modify(Set(onStart), initial) >> error.raiseError[F, A]
              }
          }
        }

      def tryModifyState(from: Set[NodeState], to: NodeState): F[Unit] =
        getNodeState.flatMap { initial =>
          modify(from, to).flatMap {
            case NodeStateTransition.Failure => InvalidNodeStateTransition(initial, from, to).raiseError[F, Unit]
            case NodeStateTransition.Success => Applicative[F].unit
          }
        }

      def tryModifyStateGetResult(from: Set[NodeState], to: NodeState): F[NodeStateTransition] =
        modify(from, to)

      def nodeStates: Stream[F, NodeState] =
        nodeStateTopic.subscribe(maxQueuedNodeStates)

      private def modify(from: Set[NodeState], to: NodeState): F[NodeStateTransition] =
        nodeState
          .modify[NodeStateTransition] {
            case state if from.contains(state) => (to, NodeStateTransition.Success)
            case state                         => (state, NodeStateTransition.Failure)
          }
          .flatTap {
            case NodeStateTransition.Success => nodeStateTopic.publish1(to).void
            case _                           => Applicative[F].unit
          }

      def setJoiningGracePeriod: F[Unit] =
        joiningGracePeriod.set(joiningGraceRounds)

      def clearJoiningGracePeriod: F[Unit] =
        joiningGracePeriod.set(0)

      def decrementJoiningGracePeriod: F[Unit] =
        joiningGracePeriod.update(n => math.max(0, n - 1))

      def isInJoiningGracePeriod: F[Boolean] =
        joiningGracePeriod.get.map(_ > 0)

      def setRecoveryDownload: F[Unit] =
        recoveryDownloadRef.set(true)

      def clearRecoveryDownload: F[Unit] =
        recoveryDownloadRef.set(false)

      def isRecoveryDownload: F[Boolean] =
        recoveryDownloadRef.get

      def setValidatorMode: F[Unit] =
        validatorModeRef.set(true)

      def isValidatorMode: F[Boolean] =
        validatorModeRef.get
    }
}
