package org.tessellation.sdk.infrastructure.snapshot

import cats.syntax.functor._
import cats.syntax.applicative._
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.{Applicative, Eq, Order}

import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.Block
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.schema.snapshot
import org.tessellation.security.signature.Signed
import org.tessellation.sdk.domain.consensus.ConsensusValidator
import org.tessellation.sdk.domain.consensus.ConsensusValidator.InvalidArtifact
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger

object SnapshotConsensusValidator {
  def make[
    F[_]: Async,
    T <: Transaction,
    B <: Block[T],
    P <: StateProof,
    Event,
    Artifact <: Snapshot[T, B]: Eq,
    Context <: SnapshotInfo[P]
  ](
    snapshotConsensusFunctions: SnapshotConsensusFunctions[F, T, B, P, Event, Artifact, Context]
  ) = new ConsensusValidator[F, Artifact, Context] {
    def validateArtifact(lastSignedArtifact: Signed[Artifact], lastContext: Context)(
      artifact: Artifact
    ): F[Either[InvalidArtifact, (Artifact, Context)]] = {
      import snapshotConsensusFunctions._
      val events = extractEvents(artifact)
      val trigger = extractTrigger(lastSignedArtifact, artifact)

      def recreateProposal: F[(Artifact, Context)] =
        createProposalArtifact(lastSignedArtifact.ordinal, lastSignedArtifact, lastContext, trigger, events)
          .map(proposal => (proposal._1, proposal._2))

      recreateProposal.map {
        case res @ (recreatedArtifact, _) if recreatedArtifact === artifact => res.asRight[InvalidArtifact]
        case _                                                              => ArtifactMismatch.asLeft[(Artifact, Context)]
      }
    }
  }
}
