package io.constellationnetwork.dag.l0.domain.snapshot

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.gossip.event.{ChainTip, ForkRecoveryInfo, RecoveryPeerHint}
import io.constellationnetwork.schema.node.{InvalidNodeStateTransition, NodeState}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}

import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Service encapsulating fork recovery business logic.
  *
  * Extracted from Main.scala wiring to separate concerns: - getLocalChainTip: provides the node's current chain tip for fork detection -
  * onForkDetected: handles recovery state transitions when fork divergence is detected
  */
trait ForkRecoveryService[F[_]] {
  def getLocalChainTip: F[Option[ChainTip]]
  def onForkDetected(info: ForkRecoveryInfo): F[Unit]
}

object ForkRecoveryService {

  def make[F[_]: Async](
    nodeStorage: NodeStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    recoveryPeerHint: RecoveryPeerHint[F]
  )(implicit logger: SelfAwareStructuredLogger[F]): ForkRecoveryService[F] =
    new ForkRecoveryService[F] {

      val getLocalChainTip: F[Option[ChainTip]] =
        lastGlobalSnapshotStorage.getCombined.map(
          _.map { case (hashed, _) => ChainTip(hashed.ordinal, hashed.hash) }
        )

      def onForkDetected(info: ForkRecoveryInfo): F[Unit] =
        nodeStorage.getNodeState.flatMap { currentState =>
          if (
            currentState === NodeState.Observing ||
            currentState === NodeState.WaitingForObserving ||
            currentState === NodeState.DownloadInProgress ||
            currentState === NodeState.WaitingForDownload
          ) {
            logger.info(
              s"Fork divergence suppressed: node in $currentState (recovery in progress). " +
                s"local=${info.localOrdinal.value.value} majority=${info.majorityOrdinal.value.value}"
            )
          } else {
            logger.warn(
              s"Fork divergence detected: local=${info.localOrdinal.value.value} " +
                s"majority=${info.majorityOrdinal.value.value} lag=${info.lag} " +
                s"majorityPeers=${info.majorityPeers.size}"
            ) >>
              // Attempt state transition first. Only set recovery flags if the transition
              // succeeds — avoids leaving stale flags when the node is in an unexpected state.
              // We only catch InvalidNodeStateTransition (wrong source state), not broader errors.
              nodeStorage
                .tryModifyState(NodeState.Ready, NodeState.WaitingForDownload)
                .recoverWith {
                  case _: InvalidNodeStateTransition =>
                    nodeStorage.tryModifyState(NodeState.WaitingForReady, NodeState.WaitingForDownload)
                }
                .flatMap { _ =>
                  recoveryPeerHint.setPreferredPeers(info.majorityPeers) >>
                    nodeStorage.setRecoveryDownload
                }
          }
        }
    }
}
