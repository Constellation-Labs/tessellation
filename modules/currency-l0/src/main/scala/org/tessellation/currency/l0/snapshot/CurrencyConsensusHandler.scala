package org.tessellation.currency.l0.snapshot

import cats.effect.Async
import cats.syntax.semigroupk._

import org.tessellation.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome}
import org.tessellation.currency.schema.currency.CurrencySnapshotContext
import org.tessellation.node.shared.infrastructure.consensus.ConsensusRumorHandlers
import org.tessellation.node.shared.infrastructure.gossip.RumorHandler
import org.tessellation.node.shared.snapshot.currency._

import io.circe.Decoder

object CurrencyConsensusHandler {
  def make[F[_]: Async](
    storage: CurrencyConsensusStorage[F],
    manager: CurrencyConsensusManager[F],
    fns: CurrencySnapshotConsensusFunctions[F]
  )(implicit eventDecoder: Decoder[CurrencySnapshotEvent]): RumorHandler[F] = {
    val all = new ConsensusRumorHandlers[
      F,
      CurrencySnapshotEvent,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ](storage, manager, fns)

    all.eventHandler <+>
      all.facilityHandler <+>
      all.proposalHandler <+>
      all.signatureHandler <+>
      all.binarySignatureHandler <+>
      all.peerDeclarationAckHandler <+>
      all.artifactHandler <+>
      all.withdrawPeerDeclarationHandler
  }
}
