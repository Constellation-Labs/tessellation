package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.semigroupk._

import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.GlobalSnapshotEvent
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema.{GlobalConsensusKind, GlobalConsensusOutcome}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusRumorHandlers
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.security.HasherSelector

object GlobalConsensusHandler {
  def make[F[_]: Async: HasherSelector](
    storage: GlobalConsensusStorage[F],
    manager: GlobalConsensusManager[F],
    fns: GlobalSnapshotConsensusFunctions[F]
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
    ](storage, manager, fns)

    all.eventHandler <+>
      all.facilityHandler <+>
      all.proposalHandler <+>
      all.signatureHandler <+>
      all.peerDeclarationAckHandler <+>
      all.artifactHandler <+>
      all.withdrawPeerDeclarationHandler
  }
}
