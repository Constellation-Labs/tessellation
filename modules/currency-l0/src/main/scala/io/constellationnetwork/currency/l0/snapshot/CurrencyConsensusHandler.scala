package io.constellationnetwork.currency.l0.snapshot

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.semigroupk._

import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusRumorHandlers
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.StallReport
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusCommand, TimeoutAggregator}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerDeclaration
import io.constellationnetwork.node.shared.infrastructure.gossip.{ExcludeSelfOrigin, RumorHandler}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.security.HasherSelector

import io.circe.Decoder

object CurrencyConsensusHandler {
  def make[F[_]: Async: HasherSelector](
    queue: Queue[F, ConsensusCommand],
    timeoutAggregator: TimeoutAggregator[F]
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

    val stallReportHandler: RumorHandler[F] =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[CurrencySnapshotKey, StallReport]](ExcludeSelfOrigin) { rumor =>
        timeoutAggregator.addStallReport(rumor.origin, rumor.content.key, rumor.content.declaration)
      }

    all.facilityHandler <+>
      all.proposalHandler <+>
      all.signatureHandler <+>
      all.binarySignatureHandler <+>
      all.ackHandler <+>
      all.artifactHandler <+>
      all.withdrawHandler <+>
      stallReportHandler
  }
}
