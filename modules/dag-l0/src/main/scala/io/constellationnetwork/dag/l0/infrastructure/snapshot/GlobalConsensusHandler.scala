package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.semigroupk._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusRumorHandlers
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.StallReport
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusCommand, TimeoutAggregator}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.gossip.{ExcludeSelfOrigin, RumorHandler}
import io.constellationnetwork.security.HasherSelector

object GlobalConsensusHandler {
  def make[F[_]: Async: HasherSelector](
    queue: Queue[F, ConsensusCommand],
    timeoutAggregator: TimeoutAggregator[F]
  ): RumorHandler[F] = {
    val all = new ConsensusRumorHandlers[
      F,
      GlobalSnapshotEvent,
      GlobalSnapshotKey,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      GlobalSnapshotStatus,
      GlobalConsensusOutcome,
      GlobalConsensusKind
    ](queue)

    // StallReport handler routes directly to the TimeoutAggregator (event-driven).
    val stallReportHandler: RumorHandler[F] =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[GlobalSnapshotKey, StallReport]](ExcludeSelfOrigin) { rumor =>
        timeoutAggregator.addStallReport(rumor.origin, rumor.content.key, rumor.content.declaration)
      }

    all.facilityHandler <+>
      all.proposalHandler <+>
      all.signatureHandler <+>
      all.ackHandler <+>
      all.artifactHandler <+>
      all.withdrawHandler <+>
      stallReportHandler
  }
}
