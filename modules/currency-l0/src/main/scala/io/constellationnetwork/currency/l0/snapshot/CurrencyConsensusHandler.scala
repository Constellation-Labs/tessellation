package io.constellationnetwork.currency.l0.snapshot

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.semigroupk._

import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusRumorHandlers
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.security.HasherSelector

import io.circe.Decoder

object CurrencyConsensusHandler {
  def make[F[_]: Async: HasherSelector](
    queue: Queue[F, ConsensusCommand[CurrencySnapshotKey, CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencyConsensusOutcome]]
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
    ](queue)

    all.facilityHandler <+>
      all.proposalHandler <+>
      all.signatureHandler <+>
      all.binarySignatureHandler <+>
      all.ackHandler <+>
      all.artifactHandler <+>
      all.withdrawHandler <+>
      all.viewChangeVoteHandler <+>
      all.timeoutVoteHandler <+>
      all.evictionVoteHandler <+>
      all.admissionVoteHandler <+>
      all.assembledVccHandler
  }
}
