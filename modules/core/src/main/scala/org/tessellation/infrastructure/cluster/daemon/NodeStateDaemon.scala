package org.tessellation.infrastructure.cluster.daemon

import cats.effect.{Async, Spawn}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.domain.Daemon
import org.tessellation.domain.gossip.Gossip
import org.tessellation.domain.node.NodeStorage
import org.tessellation.schema.node.NodeState

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait NodeStateDaemon[F[_]] extends Daemon[F] {}

object NodeStateDaemon {

  def make[F[_]: Async](nodeStorage: NodeStorage[F], gossip: Gossip[F]): NodeStateDaemon[F] = new NodeStateDaemon[F] {
    private val logger = Slf4jLogger.getLogger[F]

    def start: F[Unit] =
      Spawn[F].start(spreadNodeState).void

    private def spreadNodeState: F[Unit] =
      nodeStorage.nodeState$
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
