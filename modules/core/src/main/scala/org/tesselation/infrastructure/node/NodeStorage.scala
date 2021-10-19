package org.tesselation.infrastructure.node

import cats.effect.std.Queue
import cats.effect.{Concurrent, Ref}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import org.tesselation.domain.node.NodeStorage
import org.tesselation.schema.node.{InvalidNodeStateTransition, NodeState, NodeStateTransition}

import fs2._

object NodeStorage {

  def make[F[_]: Concurrent: MonadThrow: Ref.Make]: F[NodeStorage[F]] =
    Ref.of[F, NodeState](NodeState.Initial) >>= { ref =>
      Queue.unbounded[F, NodeState] >>= { queue =>
        queue.offer(NodeState.Initial).map { _ =>
          make(ref, queue)
        }
      }
    }

  def make[F[_]: MonadThrow](nodeState: Ref[F, NodeState], nodeStateQueue: Queue[F, NodeState]): NodeStorage[F] =
    new NodeStorage[F] {
      def getNodeState: F[NodeState] = nodeState.get

      def setNodeState(state: NodeState): F[Unit] =
        nodeState.set(state) >> nodeStateQueue.offer(state)

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

      def nodeState$ : Stream[F, NodeState] =
        Stream.fromQueueUnterminated(nodeStateQueue)

      private def modify(from: Set[NodeState], to: NodeState): F[NodeStateTransition] =
        nodeState
          .modify[NodeStateTransition] {
            case state if from.contains(state) => (to, NodeStateTransition.Success)
            case state                         => (state, NodeStateTransition.Failure)
          }
          .flatTap {
            case NodeStateTransition.Success => nodeStateQueue.offer(to)
            case _                           => Applicative[F].unit
          }
    }
}
