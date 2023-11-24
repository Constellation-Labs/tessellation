package org.tessellation.node.shared.infrastructure.node

import cats.effect.{Concurrent, Ref}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.schema.node.{InvalidNodeStateTransition, NodeState, NodeStateTransition}

import fs2._
import fs2.concurrent.Topic

object NodeStorage {

  private val maxQueuedNodeStates = 1000

  def make[F[_]: Concurrent: Ref.Make]: F[NodeStorage[F]] =
    Ref.of[F, NodeState](NodeState.Initial) >>= { ref =>
      Topic[F, NodeState] >>= { topic =>
        topic.publish1(NodeState.Initial).map { _ =>
          make(ref, topic)
        }
      }
    }

  def make[F[_]: MonadThrow](nodeState: Ref[F, NodeState], nodeStateTopic: Topic[F, NodeState]): NodeStorage[F] =
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
    }
}
