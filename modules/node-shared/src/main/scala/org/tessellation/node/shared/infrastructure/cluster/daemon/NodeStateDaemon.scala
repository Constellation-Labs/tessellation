package org.tessellation.node.shared.infrastructure.cluster.daemon

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.node.shared.domain.Daemon
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.schema.node.NodeState

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait NodeStateDaemon[F[_]] extends Daemon[F] {}

object NodeStateDaemon {

  def make[F[_]: Async](nodeStorage: NodeStorage[F], gossip: Gossip[F])(implicit S: Supervisor[F]): NodeStateDaemon[F] =
    new NodeStateDaemon[F] {
      private val logger = Slf4jLogger.getLogger[F]

      def start: F[Unit] =
        S.supervise(spreadNodeState).void

      private def spreadNodeState: F[Unit] =
        nodeStorage.nodeStates
          .filter(NodeState.toBroadcast.contains)
          .evalTap { nodeState =>
            logger.info(s"Node state changed to=${nodeState.show}") >>
              gossip.spread(nodeState).handleErrorWith { error =>
                logger.error(error)(s"NodeState spread error=${error.getMessage}")
              }
          }
          .compile
          .drain
    }

}
