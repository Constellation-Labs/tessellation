package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.semigroupk._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusRumorHandlers
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.security.HasherSelector

object GlobalConsensusHandler {
  def make[F[_]: Async: HasherSelector](
    queue: Queue[F, ConsensusCommand[GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext, GlobalConsensusOutcome]]
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

    all.facilityHandler <+>
      all.proposalHandler <+>
      all.signatureHandler <+>
      all.ackHandler <+>
      all.artifactHandler <+>
      all.withdrawHandler <+>
      all.viewChangeVoteHandler <+>
      all.timeoutVoteHandler <+>
      all.outcomeVoteHandler <+>
      all.evictionVoteHandler <+>
      all.admissionVoteHandler <+>
      all.assembledVccHandler
  }
}
