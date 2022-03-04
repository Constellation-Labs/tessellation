package org.tessellation.sdk.infrastructure.node

import cats.MonadThrow
import cats.effect.Concurrent
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.fsm.FSM
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.domain.node.NodeStorage

import fs2._
import fs2.concurrent.Topic

object NodeStorage {

  def make[F[_]: Concurrent]: F[NodeStorage[F]] =
      Topic[F, NodeState] >>= { topic =>
        FSM.make[F, NodeState](NodeState.Initial, { (s: NodeState) => topic.publish1(s).void }).flatMap { fsm =>
          topic.publish1(NodeState.Initial).map { _ =>
            make(fsm, topic)
          }
      }
    }

  def make[F[_]: MonadThrow](fsm: FSM[F, NodeState], nodeStateTopic: Topic[F, NodeState]): NodeStorage[F] =
    new NodeStorage[F] with FSM[F, NodeState] {
      def state: F[NodeState] = fsm.state

      def set(to: NodeState) = fsm.set(to)

      def tryModify[R](from: Set[NodeState], through: NodeState, to: R => NodeState)(fn: => F[R]): F[R] = 
        fsm.tryModify(from, through, to)(fn)

      def tryModify(from: Set[NodeState], to: NodeState): F[Unit] =
        fsm.tryModify(from, to)

      def canJoinCluster: F[Boolean] = state.map(_ == NodeState.ReadyToJoin)

      def nodeStates: Stream[F, NodeState] =
        nodeStateTopic.subscribe(1)
    }
}
