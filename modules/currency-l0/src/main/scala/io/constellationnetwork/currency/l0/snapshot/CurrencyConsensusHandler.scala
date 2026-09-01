package io.constellationnetwork.currency.l0.snapshot

import cats.data.Kleisli
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.l0.snapshot.synchronous.ConsensusRumorHandlers
import io.constellationnetwork.currency.l0.snapshot.synchronous.declaration.AttemptDomain
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.security.HasherSelector

object CurrencyConsensusHandler {
  def make[F[_]: Async: HasherSelector](
    storage: CurrencyConsensusStorage[F],
    manager: CurrencyConsensusManager[F]
  ): RumorHandler[F] = {
    val expectedDomain = (key: CurrencySnapshotKey) =>
      storage
        .getState(key)
        .flatMap(_.traverse { state =>
          HasherSelector[F].withCurrent { implicit hasher =>
            state.lastOutcome.finished.signedMajorityArtifact.hash.map { parentArtifactHash =>
              AttemptDomain(
                CurrencySnapshotConsensusOps.attemptFacilitatorsHash(state.status),
                parentArtifactHash,
                state.lastOutcome.finished.binaryArtifactHash
              )
            }
          }
        })

    val all = new ConsensusRumorHandlers[
      F,
      CurrencySnapshotEvent,
      CurrencySnapshotKey,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      CurrencySnapshotStatus,
      CurrencyConsensusOutcome,
      CurrencyConsensusKind
    ](storage, manager, expectedDomain)

    Kleisli { input =>
      all.facilityHandler
        .run(input)
        .orElse(all.proposalHandler.run(input))
        .orElse(all.signatureHandler.run(input))
        .orElse(all.binarySignatureHandler.run(input))
        .orElse(all.peerDeclarationAckHandler.run(input))
        .orElse(all.artifactHandler.run(input))
        .orElse(all.withdrawPeerDeclarationHandler.run(input))
    }
  }
}
