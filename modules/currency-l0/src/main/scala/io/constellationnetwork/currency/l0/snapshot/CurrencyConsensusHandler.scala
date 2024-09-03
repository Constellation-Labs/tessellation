package io.constellationnetwork.currency.l0.snapshot

import cats.effect.Async
import cats.syntax.semigroupk._

import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusRumorHandlers
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.security.HasherSelector

import io.circe.Decoder

object CurrencyConsensusHandler {
  def make[F[_]: Async: HasherSelector](
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
