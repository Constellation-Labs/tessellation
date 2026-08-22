package io.constellationnetwork.currency.l0.snapshot

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusOutcome, Finished}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotContext}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{CurrencyStateProofSelector, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, HasherSelector, SecurityProvider}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

/** One canonical constructor and validator for Currency L0's first incremental consensus root.
  *
  * The root is independently created by the genesis program. Certified consensus may use it as authority only when the configured
  * activation is at genesis and this exact default operational shape is retained. Reconstructing the value here keeps genesis creation and
  * download validation on one typed definition; no new serialization or hash scheme is introduced.
  *
  * `snapshotHash` and `binaryArtifactHash` are recomputed from the signed values during validation. The signed binary must embed the exact
  * signed Currency artifact and carry the same complete signer set. No candidate-provided scalar is accepted as root authority.
  */
object CurrencyCertifiedGenesisOutcome {

  def seed(
    snapshot: Signed[CurrencyIncrementalSnapshot],
    binary: Hashed[StateChannelSnapshotBinary],
    context: CurrencySnapshotContext,
    snapshotHash: Hash
  ): CurrencyConsensusOutcome = {
    val proofSigners = snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)

    CurrencyConsensusOutcome(
      snapshot.ordinal,
      Facilitators(proofSigners),
      RemovedFacilitators.empty,
      WithdrawnFacilitators.empty,
      EligibleFacilitators(proofSigners),
      Finished(
        snapshot,
        binary.hash,
        context,
        EventTrigger,
        Candidates.empty,
        Hash.empty,
        snapshotHash,
        certifiedBinary = binary.signed.some
      ),
      recentProofSizes = SortedMap(snapshot.ordinal -> snapshot.proofs.size.toInt),
      expandedBeyondSingleton = Some(proofSigners.size > 1)
    )
  }

  def validate[F[_]: Async: Parallel: HasherSelector: JsonSerializer: SecurityProvider](
    candidate: CurrencyConsensusOutcome,
    seedlistPeerIds: Set[PeerId] = Set.empty
  ): F[Either[String, Unit]] =
    HasherSelector[F].withCurrent { implicit hasher =>
      candidate.finished.certifiedBinary match {
        case None => "genesis_binary_missing".asLeft[Unit].pure[F]
        case Some(binary) =>
          val artifact = candidate.finished.signedMajorityArtifact
          val artifactSignerIds = artifact.proofs.toSortedSet.toList.map(_.id.toPeerId)
          val binarySignerIds = binary.proofs.toSortedSet.toList.map(_.id.toPeerId)
          val artifactSigners = artifactSignerIds.toSet
          val binarySigners = binarySignerIds.toSet
          implicit val stateProofSelector: CurrencyStateProofSelector = CurrencyStateProofSelector.instance

          for {
            artifactSignatureValid <- artifact.hasValidSignature[F]
            binarySignatureValid <- binary.hasValidSignature[F]
            hashedArtifact <- artifact.toHashed[F]
            hashedBinary <- binary.toHashed[F]
            embeddedArtifact <- JsonSerializer[F].deserialize[Signed[CurrencyIncrementalSnapshot]](binary.value.content)
            // The first incremental artifact carries the full-genesis state at ordinal 0.
            // Recomputing from the candidate context authenticates that otherwise unsigned
            // peer-supplied context without selecting the ordinal-1 proof format.
            contextStateProof <- candidate.finished.context.snapshotInfo.stateProof[F](SnapshotOrdinal.MinValue)
            expected = seed(artifact, hashedBinary, candidate.finished.context, hashedArtifact.hash)
          } yield
            for {
              _ <- Either.cond(artifactSigners.nonEmpty, (), "genesis_proof_signers_empty")
              _ <- Either.cond(artifactSignerIds.size === artifactSigners.size, (), "genesis_artifact_duplicate_signer")
              _ <- Either.cond(binarySignerIds.size === binarySigners.size, (), "genesis_binary_duplicate_signer")
              _ <- Either.cond(
                seedlistPeerIds.isEmpty || artifactSigners.forall(seedlistPeerIds.contains),
                (),
                "genesis_artifact_signer_not_seedlisted"
              )
              _ <- Either.cond(artifactSignatureValid, (), "genesis_artifact_signature_invalid")
              _ <- Either.cond(binarySignatureValid, (), "genesis_binary_signature_invalid")
              _ <- Either.cond(binarySigners === artifactSigners, (), "genesis_binary_signers_mismatch")
              decoded <- embeddedArtifact.leftMap(error => s"genesis_binary_artifact_decode:${error.getMessage}")
              _ <- Either.cond(
                Signed.sameValueAndProofs(decoded, artifact),
                (),
                "genesis_binary_artifact_mismatch"
              )
              _ <- Either.cond(
                contextStateProof === artifact.value.stateProof,
                (),
                "genesis_context_state_proof_mismatch"
              )
              _ <- Either.cond(candidate === expected, (), "genesis_outcome_not_proof_signer_root")
            } yield ()
      }
    }

  /** Bind proof-derived root authority to the exact signed artifact already accepted into local Currency snapshot storage. */
  def validateAgainstLocalArtifact[F[_]: Async: Parallel: HasherSelector: JsonSerializer: SecurityProvider](
    candidate: CurrencyConsensusOutcome,
    localArtifact: Signed[CurrencyIncrementalSnapshot],
    seedlistPeerIds: Set[PeerId] = Set.empty
  ): F[Either[String, Unit]] =
    validate[F](candidate, seedlistPeerIds).map(
      _.flatMap(_ =>
        Either.cond(
          Signed.sameValueAndProofs(candidate.finished.signedMajorityArtifact, localArtifact),
          (),
          "genesis_artifact_not_locally_validated"
        )
      )
    )
}
