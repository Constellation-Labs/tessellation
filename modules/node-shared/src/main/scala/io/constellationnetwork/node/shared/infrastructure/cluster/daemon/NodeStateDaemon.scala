package io.constellationnetwork.node.shared.infrastructure.cluster.daemon

import cats.effect.std.Supervisor
import cats.effect.{Async, Ref}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.node.NodeState

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait NodeStateDaemon[F[_]] extends Daemon[F] {}

object NodeStateDaemon {

  def make[F[_]: Async: Metrics](nodeStorage: NodeStorage[F], gossip: Gossip[F])(implicit S: Supervisor[F]): NodeStateDaemon[F] =
    new NodeStateDaemon[F] {
      private val logger = Slf4jLogger.getLogger[F]

      def start: F[Unit] =
        S.supervise(spreadNodeState).void

      // Emits `dag_node_state_transition_total{from, to}` for every state change observed on
      // `nodeStorage.nodeStates`. Diagnostic instrumentation: source-node logs show community
      // peers cycling WFR -> Leaving -> Offline, but the trigger point isn't always obvious. With
      // this counter, /metrics on every node reports the from/to of every transition since boot,
      // so we can identify which call path (forceLeaveFromInitFailures, Cluster.leaveCluster,
      // gossip-driven, etc.) is firing on a wedged peer without needing local log access.
      private def spreadNodeState: F[Unit] =
        Ref[F].of(none[NodeState]).flatMap { prevRef =>
          nodeStorage.nodeStates.evalTap { newState =>
            prevRef.getAndSet(newState.some).flatMap {
              case Some(prev) if prev != newState => emitTransition(prev.entryName, newState.entryName)
              case None                           => emitTransition("(initial)", newState.entryName)
              case _                              => Async[F].unit
            }
          }
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

      private def emitTransition(from: String, to: String): F[Unit] =
        Metrics[F].incrementCounter(
          "dag_node_state_transition_total",
          Seq(
            Metrics.unsafeLabelName("from") -> from,
            Metrics.unsafeLabelName("to") -> to
          )
        )
    }

}
