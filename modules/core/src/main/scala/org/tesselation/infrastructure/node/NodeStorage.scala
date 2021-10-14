package org.tesselation.infrastructure.node

import cats.effect.Ref
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import org.tesselation.domain.node.NodeStorage
import org.tesselation.schema.node.{InvalidNodeStateTransition, NodeState, NodeStateTransition}

object NodeStorage {

  def make[F[_]: MonadThrow: Ref.Make]: F[NodeStorage[F]] =
    Ref.of[F, NodeState](NodeState.Initial).map(make(_))

  def make[F[_]: MonadThrow](nodeState: Ref[F, NodeState]): NodeStorage[F] = new NodeStorage[F] {
    def getNodeState: F[NodeState] = nodeState.get

    def setNodeState(state: NodeState): F[Unit] =
      nodeState.set(state)

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

    private def modify(from: Set[NodeState], to: NodeState): F[NodeStateTransition] =
      nodeState.modify {
        case state if from.contains(state) => (to, NodeStateTransition.Success)
        case state                         => (state, NodeStateTransition.Failure)
      }
  }
}
