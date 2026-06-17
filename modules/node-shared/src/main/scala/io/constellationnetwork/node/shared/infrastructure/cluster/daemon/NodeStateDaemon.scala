package io.constellationnetwork.node.shared.infrastructure.cluster.daemon

import cats.effect.std.Supervisor
import cats.effect.{Async, Ref}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.domain.Daemon
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.node.NodeState

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait NodeStateDaemon[F[_]] extends Daemon[F] {}

object NodeStateDaemon {

  def make[F[_]: Async: Metrics](
    nodeStorage: NodeStorage[F],
    gossip: Gossip[F],
    // Externally-owned Ref holding the monotonic time at which the node entered its current
    // NodeState. When provided, every observed transition refreshes the timestamp. Read by
    // Cluster.leave() to enforce a dwell-time check on recovery-path states. None preserves the
    // pre-alpha.68 behavior (no timestamp tracking, dwell check inactive).
    stateEntryAtRef: Option[Ref[F, FiniteDuration]] = None
  )(implicit S: Supervisor[F]): NodeStateDaemon[F] =
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
              case Some(prev) if prev != newState =>
                emitTransition(prev.entryName, newState.entryName) >>
                  emitCurrentState(prev.some, newState) >>
                  refreshStateEntryAt
              case None =>
                emitTransition("(initial)", newState.entryName) >>
                  emitCurrentState(none[NodeState], newState) >>
                  refreshStateEntryAt
              case _ => Async[F].unit
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

      // Local state gauge, including non-broadcast recovery states like WaitingForDownload
      // and DownloadInProgress. Source nodes may still see such peers as SessionStarted over
      // gossip, so this is the metric to read from the peer itself when diagnosing join stalls.
      private def emitCurrentState(previous: Option[NodeState], current: NodeState): F[Unit] = {
        val stateLabel = Metrics.unsafeLabelName("state")
        val clearPrevious = previous.filterNot(_ == current).fold(Async[F].unit) { state =>
          Metrics[F].updateGauge(
            "dag_node_state_current",
            0L,
            Seq(stateLabel -> state.entryName)
          )
        }

        clearPrevious >>
          Metrics[F].updateGauge(
            "dag_node_state_current",
            1L,
            Seq(stateLabel -> current.entryName)
          )
      }

      // Reset the state-entry timestamp on every transition. The Cluster.leave() guard reads this
      // to refuse external leave requests that fire while a recovery-path state is still within
      // its dwell window. Skipped when no Ref is wired (currency-l0 / dag-l1 paths today).
      private def refreshStateEntryAt: F[Unit] =
        stateEntryAtRef match {
          case None      => Async[F].unit
          case Some(ref) => Async[F].monotonic.flatMap(ref.set)
        }
    }

}
