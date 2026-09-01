package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._
import cats.{Monad, Parallel}

import scala.collection.immutable.SortedSet

import io.constellationnetwork.dag.l0.domain.snapshot.recovery.Gl0RecoverySeedCommittee
import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.consensus.ProposalValue
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{SnapshotOrdinal, _}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

import eu.timepit.refined.auto._

/** Fail-closed trust boundary for a peer-supplied v35 DAG outcome.
  *
  * Outside the explicit permissioned-recovery policy, a QC cannot authenticate the committee declared inside that same QC. A local outcome
  * sidecar also cannot authenticate all of its derived operational fields, so it is never download authority. Every acceptance path begins
  * at one independently checkable public root:
  *
  *   - the locally downloaded/state-proof-validated A-1 snapshot at ordinal-gated activation; or
  *   - the canonical signed first incremental snapshot when certification is active from genesis; or
  *   - the latest explicit recovery epoch, reconstructed from its first successor's ordinary QC after the second successor carries that QC
  *     publicly, plus the independently validated public parent selected by the permissioned recovery procedure.
  *
  * It then folds the relevant contiguous public artifact chain and each child-carried parent certificate. Every QC authenticates both the
  * authority that certified its round and the exact full/Core authority allowed to certify the next round, so historical replay never
  * executes a joiner's current committee policy against old data. Interior SnapshotInfo/context preimages may follow ordinary logarithmic
  * retention: their hashes are certified, while complete state-proof/context validation remains mandatory at the independently trusted root
  * and downloaded terminal. For a later recovery only the latest reset-to-tip segment is required; older recovery epochs are superseded.
  * The terminal certificate comes from the authenticated peer outcome. No prefix is installed until the complete fold authenticates that
  * terminal outcome and its certified operational-state commitment.
  *
  * The resulting frozen state is passed to the ordinary `CertifiedConsensus` verifier. No alternate encoder, canonicalizer, hash, QC
  * verifier, or committee rule is introduced. Selected recovery nodes still require the exact env-authorized anchor and all-member barrier;
  * an unconfigured community node accepts only the first successor's quorum-certified boundary once carried by the second successor—not a
  * standalone synthetic outcome, a source-private terminal QC, or a single peer assertion.
  */
object GlobalCertifiedDownloadValidator {

  /** Execute a replay state machine without retaining prior cursors. Production cursors contain only the authenticated predecessor and at
    * most two public frames; using `tailRecM` here makes the heap/stack bound independent of the concrete effect runtime.
    */
  private[snapshot] def runConstantMemoryReplay[F[_]: Monad, Cursor, Result](
    initial: Cursor
  )(step: Cursor => F[Either[Cursor, Result]]): F[Result] =
    Monad[F].tailRecM(initial)(step)

  /** A historical QC may advance authority only from the exact full/Core sets certified by its already-authenticated predecessor. This is
    * deliberately independent of the joining node's current membership policy.
    */
  private[snapshot] def validateAuthorityContinuity(
    value: ProposalValue,
    inherited: CertifiedConsensus.CertifiedRoundAuthorityV1
  ): Either[String, Unit] =
    Either
      .cond(
        value.roundStartFacilitators === inherited.facilitators,
        (),
        "certified_authority_full_continuity_mismatch"
      )
      .productL(
        Either.cond(
          value.roundStartCore === inherited.core,
          (),
          "certified_authority_core_continuity_mismatch"
        )
      )

  /** Independently authenticated replay root. It is intentionally compact: historical authority needs the parent identity, carried
    * committee, and prior certificate only; it never needs a retained historical state-context preimage.
    */
  private final case class TrustedParent(
    key: GlobalSnapshotKey,
    artifactHash: Hash,
    authorityForNextRound: CertifiedConsensus.CertifiedRoundAuthorityV1,
    certifiedOutcome: Option[CertifiedConsensus.CertifiedOutcome],
    expandedBeyondSingleton: Option[Boolean]
  )

  private final case class PublicRound(
    key: GlobalSnapshotKey,
    artifact: Signed[GlobalIncrementalSnapshot],
    // Present only at the downloaded terminal boundary. Interior authority replay
    // verifies the QC-certified context hash and never loads the large preimage.
    context: Option[GlobalSnapshotInfo]
  )

  /** Minimal authenticated fold state for historical replay. Operational sidecars are neither loaded nor derived for interior rounds. */
  private final case class VerifiedPublicRound(
    key: GlobalSnapshotKey,
    artifactHash: Hash,
    authorityForNextRound: CertifiedConsensus.CertifiedRoundAuthorityV1,
    certifiedOutcome: Option[CertifiedConsensus.CertifiedOutcome],
    expandedBeyondSingleton: Option[Boolean]
  )

  private[snapshot] def validateGenesisRoot[F[_]: Async: Parallel: JsonSerializer: HasherSelector: SecurityProvider](
    candidate: GlobalConsensusOutcome,
    localArtifact: Signed[GlobalIncrementalSnapshot],
    localContext: GlobalSnapshotInfo,
    seedlistPeerIds: Set[PeerId] = Set.empty
  )(implicit globalStateProofSelector: GlobalStateProofSelector): F[Either[String, Unit]] =
    HasherSelector[F].withCurrent { implicit hasher =>
      val artifact = candidate.finished.signedMajorityArtifact
      val signerIds = artifact.proofs.toSortedSet.toList.map(_.id.toPeerId)
      val committee = SortedSet.from(signerIds)

      for {
        signatureValid <- artifact.hasValidSignature[F]
        snapshotHash <- GlobalSnapshotArtifactHasher.currentHash[F](artifact.value)
        // The first incremental artifact carries the full-genesis state at ordinal 0.
        // Recompute it from the peer-supplied context before that context can become
        // certified-lineage authority. Using key 1 here would select the wrong proof era
        // when a format transition is configured immediately after genesis.
        contextStateProof <- candidate.finished.context.stateProof[F](SnapshotOrdinal.MinValue)
        expected = GlobalRecoverySeedOutcome.seed(
          artifact,
          candidate.finished.context,
          snapshotHash,
          committee
        )
      } yield
        for {
          _ <- Either.cond(committee.nonEmpty, (), "genesis_proof_signers_empty")
          _ <- Either.cond(signerIds.size === committee.size, (), "genesis_artifact_duplicate_signer")
          _ <- Either.cond(
            seedlistPeerIds.isEmpty || committee.forall(seedlistPeerIds.contains),
            (),
            "genesis_artifact_signer_not_seedlisted"
          )
          _ <- Either.cond(signatureValid, (), "genesis_artifact_signature_invalid")
          _ <- Either.cond(
            Signed.sameValueAndProofs(artifact, localArtifact),
            (),
            "genesis_artifact_not_locally_validated"
          )
          _ <- Either.cond(
            contextStateProof === artifact.value.stateProof,
            (),
            "genesis_context_state_proof_mismatch"
          )
          _ <- Either.cond(candidate.finished.context === localContext, (), "genesis_context_not_locally_validated")
          _ <- Either.cond(candidate === expected, (), "genesis_outcome_not_proof_signer_root")
        } yield ()
    }

  /** Reconstruct the activation parent's live consensus identity with the current hasher.
    *
    * State-proof validation remains ordinal-selected, and proposal parent links retain their historical V1 projection.
    * `Finished.snapshotHash`, however, is produced by live consensus with the current hasher, so the exact-activation bridge must preserve
    * that identity. Every public network crossed the Kryo-to-JSON boundary before v35; this bridge adds no Kryo fallback or new cross-era
    * contract.
    */
  private[snapshot] def reconstructActivationParentFinished[F[_]: Async: HasherSelector](
    snapshot: Signed[GlobalIncrementalSnapshot],
    context: GlobalSnapshotInfo
  ): F[Finished] =
    HasherSelector[F].withCurrent { implicit hasher =>
      GlobalSnapshotArtifactHasher.currentHash[F](snapshot.value).map { snapshotHash =>
        Finished(
          snapshot,
          context,
          EventTrigger,
          Candidates.empty,
          Hash.empty,
          snapshotHash
        )
      }
    }

  /** Resolve the only public root authorized for an ordinal-gated certified replay.
    *
    * The root is A-1, where A is the configured activation key. It is deliberately independent of the downloaded terminal key T; using T-1
    * would make a missing private sidecar silently trust a certificate whose committee is named only by that certificate.
    */
  private[snapshot] def activationParentOrdinal(
    activation: Long,
    terminal: SnapshotOrdinal
  ): Either[String, SnapshotOrdinal] =
    if (activation <= 0L) "activation_parent_unavailable_at_genesis".asLeft
    else if (activation > terminal.value.value) "activation_after_downloaded_candidate".asLeft
    else SnapshotOrdinal.unsafeApply(activation - 1L).asRight

  /** Authenticate the public A-1 artifact before its signed controller evidence can seed v35 authority.
    *
    * Snapshot storage's validated read proves the artifact/context state-proof relation, but a state proof does not authenticate the
    * artifact's signature envelope. Keep signature and unique-signer checks at this authority boundary as well: a forged or locally
    * corrupted A-1 file must not be able to name the first certified committee. The current mutable seedlist is deliberately not historical
    * authority: live activation already ran the then-current join-fenced membership policy, and a later seedlist change must not invalidate
    * the canonical signed root.
    */
  private[snapshot] def validateActivationRootArtifact[
    F[_]: Async: Parallel: JsonSerializer: HasherSelector: SecurityProvider
  ](
    expectedOrdinal: SnapshotOrdinal,
    snapshot: Signed[GlobalIncrementalSnapshot],
    context: GlobalSnapshotInfo
  )(implicit globalStateProofSelector: GlobalStateProofSelector): F[Either[String, Unit]] = {
    val signerIds = snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
    val signers = SortedSet.from(signerIds)
    val proofOrdinal =
      if (expectedOrdinal === SnapshotOrdinal.MinIncrementalValue) SnapshotOrdinal.MinValue else expectedOrdinal

    if (snapshot.ordinal =!= expectedOrdinal) "activation_artifact_ordinal_mismatch".asLeft[Unit].pure[F]
    else
      HasherSelector[F].forOrdinal(expectedOrdinal) { implicit hasher =>
        for {
          signatureValid <- snapshot.hasValidSignature[F]
          contextStateProof <- context.stateProof[F](proofOrdinal)
        } yield
          for {
            _ <- Either.cond(signers.nonEmpty, (), "activation_artifact_proof_signers_empty")
            _ <- Either.cond(signerIds.size === signers.size, (), "activation_artifact_duplicate_signer")
            _ <- Either.cond(signatureValid, (), "activation_artifact_signature_invalid")
            _ <- Either.cond(
              contextStateProof === snapshot.value.stateProof,
              (),
              "activation_context_state_proof_mismatch"
            )
          } yield ()
      }
  }

  def make[F[_]: Async: Parallel: JsonSerializer: HasherSelector: SecurityProvider: Metrics](
    config: ConsensusConfig,
    networkId: String,
    seedlistPeerIds: Set[PeerId],
    snapshotDownloadStorage: SnapshotDownloadStorage[F]
  )(implicit globalStateProofSelector: GlobalStateProofSelector): GlobalConsensusOutcome => F[Unit] = {

    def recordPublicRecoveryBoundary(outcome: String): F[Unit] =
      Metrics[F]
        .incrementCounter(
          "dag_consensus_certified_recovery_boundary_total",
          Seq(Metrics.unsafeLabelName("outcome") -> outcome)
        )
        .attempt
        .void

    /** The independently validated activation/genesis artifact authenticates its canonical carried committee. The first certified round
      * uses that exact set as both full and Core authority; live policy may change the following authority only through the first QC. A
      * historical downloader therefore never needs the old seedlist, collateral policy, selector, or Core sizing implementation.
      */
    def authorizedRootAuthority(
      trustedParent: GlobalConsensusOutcome
    )(implicit hasher: io.constellationnetwork.security.Hasher[F]): F[Either[String, CertifiedConsensus.CertifiedRoundAuthorityV1]] =
      NonEmptySet.fromSet(SortedSet.from(trustedParent.facilitators.value)) match {
        case None            => "trusted_root_authority_empty".asLeft[CertifiedConsensus.CertifiedRoundAuthorityV1].pure[F]
        case Some(authority) => CertifiedConsensus.roundAuthority[F](authority, authority).flatMap(_.asRight[String].pure[F])
      }

    def trustedParentFromOutcome(
      outcome: GlobalConsensusOutcome
    )(implicit hasher: io.constellationnetwork.security.Hasher[F]): F[Either[String, TrustedParent]] =
      authorizedRootAuthority(outcome).map(
        _.map(
          TrustedParent(
            outcome.key,
            outcome.finished.snapshotHash,
            _,
            outcome.finished.certifiedOutcome,
            outcome.expandedBeyondSingleton
          )
        )
      )

    def verifiedRoot(root: TrustedParent): F[Either[String, VerifiedPublicRound]] = {
      val nextValue = BigInt(root.key.value.value) + 1

      if (nextValue > BigInt(Long.MaxValue)) "certified_lineage_key_overflow".asLeft[VerifiedPublicRound].pure[F]
      else
        VerifiedPublicRound(
          root.key,
          root.artifactHash,
          root.authorityForNextRound,
          root.certifiedOutcome,
          root.expandedBeyondSingleton
        ).asRight[String].pure[F]
    }

    def locallyValidatedSnapshot(
      ordinal: SnapshotOrdinal
    ): F[Either[String, (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] =
      // The first incremental derives its state proof from full-genesis ordinal 0, but its artifact/signatures still follow the hasher
      // selected for ordinal 1. In development that is the current JSON hasher; preserving ordinal selection also keeps a hypothetical
      // legacy activation-parent read honest instead of silently re-hashing historical bytes with the current scheme.
      if (ordinal === SnapshotOrdinal.MinIncrementalValue)
        HasherSelector[F].forOrdinal(ordinal) { implicit hasher =>
          snapshotDownloadStorage
            .readCombinedValidatedAtProofOrdinal(ordinal, SnapshotOrdinal.MinValue)
            .map(_.toRight(s"trusted_snapshot_missing:${ordinal.value.value}"))
        }
      else
        HasherSelector[F].forOrdinal(ordinal) { implicit hasher =>
          snapshotDownloadStorage
            .readCombinedValidatedAtProofOrdinal(ordinal, ordinal)
            .map(_.toRight(s"trusted_snapshot_missing:${ordinal.value.value}"))
        }

    def exactActivationParent(candidate: GlobalConsensusOutcome): F[Either[String, TrustedParent]] = {
      val activation = config.certifiedConsensusActivationKey

      activationParentOrdinal(activation, candidate.key) match {
        case Left(error) => error.asLeft[TrustedParent].pure[F]
        case Right(parentOrdinal) =>
          val activationKey = SnapshotOrdinal.unsafeApply(activation)

          locallyValidatedSnapshot(parentOrdinal).flatMap {
            case Left(error) => error.asLeft[TrustedParent].pure[F]
            case Right((snapshot, context)) =>
              validateActivationRootArtifact(parentOrdinal, snapshot, context).flatMap(_.traverse { _ =>
                for {
                  finished <- reconstructActivationParentFinished[F](snapshot, context)
                  proofSigners = snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
                  legacy = GlobalConsensusOutcome(
                    key = parentOrdinal,
                    facilitators = Facilitators(proofSigners),
                    removedFacilitators = RemovedFacilitators.empty,
                    withdrawnFacilitators = WithdrawnFacilitators.empty,
                    eligibleFacilitators = EligibleFacilitators(proofSigners),
                    finished = finished
                  )
                  reset <- HasherSelector[F].withCurrent(implicit hasher =>
                    GlobalSnapshotConsensusStateCreator
                      .resetLegacyOutcomeForHistoricalReplay[F](activationKey, legacy)
                  )
                  trusted <- HasherSelector[F].withCurrent(implicit hasher => trustedParentFromOutcome(reset))
                  result <- trusted.leftMap(new IllegalStateException(_)).liftTo[F]
                } yield result
              })
          }
      }
    }

    def canonicalGenesisRoot: F[Either[String, TrustedParent]] =
      locallyValidatedSnapshot(CertifiedConsensusGenesis.FirstIncrementalOrdinal).flatMap {
        case Left(error) => error.asLeft[TrustedParent].pure[F]
        case Right((snapshot, context)) =>
          HasherSelector[F].withCurrent { implicit hasher =>
            for {
              snapshotHash <- GlobalSnapshotArtifactHasher.currentHash[F](snapshot.value)
              committee = SortedSet.from(snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId))
              root = GlobalRecoverySeedOutcome.seed(snapshot, context, snapshotHash, committee)
              validation <- validateGenesisRoot(root, snapshot, context, seedlistPeerIds)
              trusted <- trustedParentFromOutcome(root)
            } yield validation.productR(trusted)
          }
      }

    def replayRoot(candidate: GlobalConsensusOutcome): F[Either[String, TrustedParent]] =
      if (CertifiedConsensusGenesis.isActiveFromGenesis(config.certifiedConsensusActivationKey)) canonicalGenesisRoot
      else exactActivationParent(candidate)

    def loadPublicRound(
      ordinal: SnapshotOrdinal,
      candidate: GlobalConsensusOutcome
    ): F[Either[String, PublicRound]] = {
      val isTerminal = ordinal === candidate.key

      if (isTerminal)
        locallyValidatedSnapshot(ordinal).map(
          _.leftMap(error => s"certified_public_round_missing:$error").flatMap {
            case (locallyValidatedArtifact, context) =>
              for {
                _ <- Either.cond(locallyValidatedArtifact.value.ordinal === ordinal, (), "artifact_ordinal_mismatch")
                _ <- Either.cond(
                  locallyValidatedArtifact.value === candidate.finished.signedMajorityArtifact.value,
                  (),
                  "terminal_artifact_value_not_locally_validated"
                )
                _ <- Either.cond(
                  context === candidate.finished.context,
                  (),
                  "terminal_context_not_locally_validated"
                )
              } yield PublicRound(ordinal, candidate.finished.signedMajorityArtifact, context.some)
          }
        )
      else
        snapshotDownloadStorage.readPersisted(ordinal).map {
          _.toRight(s"certified_public_round_missing:trusted_snapshot_missing:${ordinal.value.value}").flatMap { artifact =>
            Either
              .cond(artifact.value.ordinal === ordinal, (), "artifact_ordinal_mismatch")
              .as(PublicRound(ordinal, artifact, none))
          }
        }
    }

    def advancePublicRound(
      trusted: VerifiedPublicRound,
      round: PublicRound,
      authority: CertifiedConsensus.CertifiedLineageEvidenceV1
    )(implicit hasher: io.constellationnetwork.security.Hasher[F]): F[Either[String, VerifiedPublicRound]] = {
      val certified = authority.parentOutcome
      val value = certified.proposalQc.value
      val inheritedFull = trusted.authorityForNextRound.facilitators
      val inheritedCore = trusted.authorityForNextRound.core
      val fullSet = inheritedFull.toSortedSet.toSet
      val structural = for {
        _ <- validateAuthorityContinuity(value, trusted.authorityForNextRound)
        _ <- Either.cond(round.artifact.value.ordinal === round.key, (), "artifact_ordinal_mismatch")
        _ <- Either.cond(
          round.artifact.value.lastSnapshotHash === trusted.artifactHash,
          (),
          "artifact_parent_mismatch"
        )
      } yield ()

      structural match {
        case Left(error) => error.asLeft[VerifiedPublicRound].pure[F]
        case Right(_) =>
          for {
            artifactHash <- GlobalSnapshotArtifactHasher.currentHash[F](round.artifact.value)
            contextHash <- round.context.traverse(Hasher[F].hash(_))
            bound <- CertifiedConsensus.verifyHistoricalOutcomeIdentity[F](
              certified,
              CertifiedConsensus.ConsensusDomain.DagL0,
              networkId,
              round.key.value.value,
              trusted.artifactHash,
              artifactHash,
              contextHash,
              inheritedFull,
              inheritedCore
            )
            artifactProofs <- CertifiedConsensus.verifyArtifactProofs[F, GlobalIncrementalSnapshot](
              round.artifact,
              fullSet,
              QuorumPolicy.supermajority(fullSet.size)
            )
          } yield
            bound.productR(artifactProofs).map { _ =>
              VerifiedPublicRound(
                key = round.key,
                artifactHash = artifactHash,
                authorityForNextRound = value.nextRoundAuthority,
                certifiedOutcome = certified.some,
                expandedBeyondSingleton = CertifiedConsensusGenesis
                  .nextExpandedBeyondSingleton(
                    config.certifiedConsensusActivationKey,
                    trusted.key,
                    trusted.authorityForNextRound.facilitators.size.toInt,
                    trusted.expandedBeyondSingleton,
                    value.nextRoundAuthority.facilitators.size.toInt
                  )
                  .some
              )
            }
      }
    }

    def validateTerminalOutcome(
      candidate: GlobalConsensusOutcome,
      terminalRound: PublicRound,
      verified: VerifiedPublicRound,
      certified: CertifiedConsensus.CertifiedOutcome
    )(implicit hasher: io.constellationnetwork.security.Hasher[F]): F[Either[String, Unit]] = {
      val value = certified.proposalQc.value
      val canonicalNextFull = value.nextRoundAuthority.facilitators.toSortedSet.toList

      Hasher[F].hash(candidate.toOperationalState).map { operationalStateHash =>
        for {
          terminalContext <- terminalRound.context.toRight("terminal_context_missing")
          _ <- Either.cond(candidate.key === terminalRound.key, (), "terminal_outcome_key_mismatch")
          _ <- Either.cond(candidate.facilitators.value === canonicalNextFull, (), "terminal_next_authority_mismatch")
          _ <- Either.cond(
            candidate.removedFacilitators.value === value.evictedPeers.toSet,
            (),
            "terminal_evicted_peers_mismatch"
          )
          _ <- Either.cond(candidate.withdrawnFacilitators.value.isEmpty, (), "terminal_withdrawals_not_empty")
          _ <- Either.cond(candidate.eligibleFacilitators.value.isEmpty, (), "terminal_eligible_not_empty")
          _ <- Either.cond(
            Signed.sameValueAndProofs(candidate.finished.signedMajorityArtifact, terminalRound.artifact),
            (),
            "terminal_artifact_mismatch"
          )
          _ <- Either.cond(candidate.finished.context === terminalContext, (), "terminal_context_mismatch")
          _ <- Either.cond(candidate.finished.majorityTrigger === value.trigger, (), "terminal_trigger_mismatch")
          _ <- Either.cond(
            candidate.finished.candidates.value === value.admissionNominee.toSet,
            (),
            "terminal_admission_nominee_mismatch"
          )
          _ <- Either.cond(
            candidate.finished.facilitatorsHash === value.roundStartFacilitatorsHash,
            (),
            "terminal_facilitators_hash_mismatch"
          )
          _ <- Either.cond(candidate.finished.snapshotHash === value.artifactHash, (), "terminal_snapshot_hash_mismatch")
          _ <- Either.cond(candidate.finished.certifiedOutcome.contains(certified), (), "terminal_certificate_mismatch")
          _ <- Either.cond(candidate.peerSelfHealth === value.observedSelfHealth, (), "terminal_self_health_mismatch")
          _ <- Either.cond(
            candidate.lastTimeoutCertificateVoters === value.timeoutVoters,
            (),
            "terminal_timeout_voters_mismatch"
          )
          _ <- Either.cond(
            candidate.expandedBeyondSingleton === verified.expandedBeyondSingleton,
            (),
            "terminal_singleton_state_mismatch"
          )
          _ <- Either.cond(
            operationalStateHash === value.nextOperationalStateHash,
            (),
            "terminal_operational_state_hash_mismatch"
          )
        } yield ()
      }
    }

    /** Reconstruct one publicly provable recovery-reset root.
      *
      * `round` is the first certified child after an env-authorized synthetic root. It carries no parent certificate because the root is
      * intentionally uncertified. Authority instead comes from the ordinary certificate for `round`, carried by its child or by the
      * authenticated terminal outcome. That certificate binds the exact parent hash and frozen committee. The normal public transition then
      * verifies the certificate signatures, artifact proofs, authority continuity, and terminal operational-state commitment.
      *
      * This is permissioned recovery authority without a second signed operator artifact. The independent authorization is the deployment
      * procedure itself: one controlled rollback lead, the env-selected cohort, the live seedlist/collateral preflight, an all-member
      * first-round barrier, and a coordinated full-fleet restart. Historical replay cannot re-check that event against today's mutable
      * seedlist without making later policy changes invalidate canonical history. It therefore verifies the exact canonical parent plus an
      * ordinary fixed-floor QC from the complete recovery Core. A community operator can already misuse run-rollback; that remains an
      * attributable operational fault in this permissioned network, not authority inferred from a self-declared policy hash.
      */
    def publicRecoveryRoot(
      round: PublicRound,
      certified: CertifiedConsensus.CertifiedOutcome
    ): F[Either[String, TrustedParent]] = {
      val value = certified.proposalQc.value
      val committee = value.roundStartFacilitators.toSortedSet
      val structural = for {
        _ <- ProposalValue.validate(value)
        _ <- Either.cond(
          value.key === round.key.value.value,
          (),
          s"recovery_seed_boundary_key_mismatch:${round.key.value.value}:${value.key}"
        )
        _ <- Either.cond(
          committee.size >= Gl0RecoverySeedCommittee.MinimumRecoveryCommitteeSize,
          (),
          s"recovery_seed_boundary_committee_too_small:${committee.size}"
        )
        _ <- Either.cond(
          value.roundStartCore.toSortedSet === committee,
          (),
          "recovery_seed_boundary_requires_full_committee_core"
        )
        parentOrdinal <- Either.cond(
          round.key.value.value > 0L,
          SnapshotOrdinal.unsafeApply(round.key.value.value - 1L),
          "recovery_seed_boundary_parent_underflow"
        )
      } yield parentOrdinal

      structural match {
        case Left(error) => error.asLeft[TrustedParent].pure[F]
        case Right(parentOrdinal) =>
          snapshotDownloadStorage.readPersisted(parentOrdinal).flatMap {
            case None =>
              s"recovery_seed_boundary_parent_missing:${parentOrdinal.value.value}".asLeft[TrustedParent].pure[F]
            case Some(snapshot) =>
              HasherSelector[F].withCurrent { implicit hasher =>
                for {
                  snapshotHash <- GlobalSnapshotArtifactHasher.currentHash[F](snapshot.value)
                  authority <- CertifiedConsensus.roundAuthority[F](
                    value.roundStartFacilitators,
                    value.roundStartCore
                  )
                } yield
                  for {
                    _ <- Either.cond(snapshot.value.ordinal === parentOrdinal, (), "recovery_seed_boundary_parent_ordinal_mismatch")
                    _ <- Either.cond(
                      snapshotHash === value.parentArtifactHash,
                      (),
                      "recovery_seed_boundary_parent_hash_mismatch"
                    )
                  } yield
                    TrustedParent(
                      parentOrdinal,
                      snapshotHash,
                      authority,
                      certifiedOutcome = None,
                      expandedBeyondSingleton = true.some
                    )
              }
          }
      }
    }

    /** Walk backward from the authenticated terminal outcome to the latest reset boundary.
      *
      * A fresh community validator may not retain activation-to-reset history. Searching from the activation root would therefore make an
      * otherwise public reset depend on private archive retention. Starting at the terminal finds the newest later `lineage=None` child,
      * reconstructs its root, and requires only that root's public parent plus the contiguous reset-to-tip segment. Repeated coordinated
      * recoveries consequently supersede older recovery epochs. If no later boundary exists, the ordinary activation/genesis replay path
      * remains byte-for-byte unchanged.
      */
    def latestPublicRecoveryRoot(
      candidate: GlobalConsensusOutcome
    ): F[Either[String, Option[TrustedParent]]] = {
      val firstOrdinaryReplayKey =
        if (CertifiedConsensusGenesis.isActiveFromGenesis(config.certifiedConsensusActivationKey))
          CertifiedConsensusGenesis.FirstIncrementalOrdinal.value.value + 1L
        else config.certifiedConsensusActivationKey

      // `tailRecM` is deliberate: a plain recursive `flatMap` is stack-safe in IO but
      // still leaves the heap-retention property to the effect runtime. This state
      // machine makes the O(1) live-frame bound explicit for every F implementation.
      type RecoveryCursor = (Long, Option[PublicRound])
      type RecoveryResult = Either[String, Option[TrustedParent]]
      def continue(cursor: RecoveryCursor): F[Either[RecoveryCursor, RecoveryResult]] =
        Async[F].pure(Left(cursor))
      def finish(result: RecoveryResult): F[Either[RecoveryCursor, RecoveryResult]] =
        Async[F].pure(Right(result))

      runConstantMemoryReplay[F, RecoveryCursor, RecoveryResult](
        (candidate.key.value.value, none[PublicRound]): RecoveryCursor
      ) {
        case (current, child) =>
          if (current < firstOrdinaryReplayKey)
            finish(none[TrustedParent].asRight[String])
          else {
            val ordinal = SnapshotOrdinal.unsafeApply(current)
            loadPublicRound(ordinal, candidate).flatMap {
              case Left(error)  => finish(error.asLeft[Option[TrustedParent]])
              case Right(round) =>
                // An empty child lineage is the public reset-epoch marker, not a
                // self-authenticating permissionless root. The canonical public parent,
                // complete recovery-Core QC, artifact proofs, and the permissioned
                // one-lead/full-fleet recovery procedure jointly authorize this boundary.
                // Reapplying today's mutable seedlist here would invalidate canonical
                // history after an ordinary operator policy change.
                val isLaterReset = current > firstOrdinaryReplayKey && round.artifact.value.certifiedLineage.isEmpty

                if (isLaterReset) {
                  val authority = child
                    .flatMap(_.artifact.value.certifiedLineage.map(_.parentOutcome))
                    .orElse(Option.when(ordinal === candidate.key)(candidate.finished.certifiedOutcome).flatten)

                  recordPublicRecoveryBoundary("detected") >>
                    (authority.toRight(s"recovery_seed_boundary_certificate_missing:$current") match {
                      case Left(error) =>
                        recordPublicRecoveryBoundary("rejected") >>
                          finish(error.asLeft[Option[TrustedParent]])
                      case Right(certified) =>
                        publicRecoveryRoot(round, certified).flatMap {
                          case Left(error) =>
                            recordPublicRecoveryBoundary("rejected") >>
                              finish(error.asLeft[Option[TrustedParent]])
                          case Right(root) =>
                            recordPublicRecoveryBoundary("root_reconstructed") >>
                              finish(root.some.asRight[String])
                        }
                    })
                } else if (current === firstOrdinaryReplayKey)
                  finish(none[TrustedParent].asRight[String])
                else continue((current - 1L, round.some))
            }
          }
      }
    }

    /** Verify a public certified epoch with constant heap use.
      *
      * A round's transferable certificate is carried by its child (the terminal round uses the authenticated terminal sidecar), so the fold
      * retains only the verified predecessor plus the current and next public frames. Long-range replay remains O(epoch) I/O but no longer
      * materializes every signed artifact/context in memory.
      */
    def verifyPublicSegment(
      root: TrustedParent,
      candidate: GlobalConsensusOutcome,
      terminalOutcome: CertifiedConsensus.CertifiedOutcome
    )(implicit hasher: Hasher[F]): F[Either[String, Unit]] = {
      def expectedSuccessor(key: Long): Either[String, Long] = {
        val next = BigInt(key) + 1
        Either.cond(next <= BigInt(Long.MaxValue), next.toLong, "certified_lineage_key_overflow")
      }

      type ReplayCursor = (VerifiedPublicRound, PublicRound)
      type ReplayResult = Either[String, Unit]

      def continue(cursor: ReplayCursor): F[Either[ReplayCursor, ReplayResult]] =
        Async[F].pure(Left(cursor))
      def finish(result: ReplayResult): F[Either[ReplayCursor, ReplayResult]] =
        Async[F].pure(Right(result))

      def step(
        cursor: ReplayCursor
      ): F[Either[ReplayCursor, ReplayResult]] = {
        val (trusted, current) = cursor
        val currentKey = current.key.value.value
        val terminal = current.key === candidate.key

        val nextRound =
          if (terminal) none[PublicRound].asRight[String].pure[F]
          else
            expectedSuccessor(currentKey) match {
              case Left(error) => error.asLeft[Option[PublicRound]].pure[F]
              case Right(next) => loadPublicRound(SnapshotOrdinal.unsafeApply(next), candidate).map(_.map(_.some))
            }

        nextRound.flatMap {
          case Left(error) => finish(error.asLeft[Unit])
          case Right(next) =>
            val authority = next
              .flatMap(_.artifact.value.certifiedLineage)
              .orElse(Option.when(terminal)(CertifiedConsensus.CertifiedLineageEvidenceV1(terminalOutcome)))
            val structure = for {
              expected <- expectedSuccessor(trusted.key.value.value)
              _ <- Either.cond(currentKey === expected, (), s"certified_lineage_non_contiguous:${trusted.key.value.value}:$currentKey")
              certified <- authority.toRight(s"certified_lineage_certificate_missing:$currentKey")
              _ <- Either.cond(
                certified.parentOutcome.proposalQc.value.key === currentKey,
                (),
                s"certified_lineage_authority_key_mismatch:$currentKey"
              )
            } yield certified

            structure match {
              case Left(error) => finish(error.asLeft[Unit])
              case Right(certified) =>
                CertifiedConsensus
                  .verifyHistoricalCarriedParentOutcome[F](
                    current.artifact.value.certifiedLineage,
                    trusted.certifiedOutcome,
                    CertifiedConsensus.ConsensusDomain.DagL0
                  )
                  .flatMap {
                    case Left(error) =>
                      finish(
                        s"certified_lineage_parent_invalid:$currentKey:$error"
                          .asLeft[Unit]
                      )
                    case Right(_) =>
                      advancePublicRound(trusted, current, certified).flatMap {
                        case Left(error) =>
                          finish(
                            s"certified_lineage_round_invalid:$currentKey:$error"
                              .asLeft[Unit]
                          )
                        case Right(verified) if terminal =>
                          validateTerminalOutcome(candidate, current, verified, terminalOutcome).flatMap(finish)
                        case Right(verified) =>
                          next match {
                            case Some(successor) => continue((verified, successor))
                            case None            => finish("certified_lineage_successor_missing".asLeft[Unit])
                          }
                      }
                  }
            }
        }
      }

      verifiedRoot(root).flatMap {
        case Left(error) => error.asLeft[Unit].pure[F]
        case Right(trustedRoot) =>
          expectedSuccessor(trustedRoot.key.value.value) match {
            case Left(error) => error.asLeft[Unit].pure[F]
            case Right(first) =>
              loadPublicRound(SnapshotOrdinal.unsafeApply(first), candidate).flatMap {
                case Left(error)  => error.asLeft[Unit].pure[F]
                case Right(round) => runConstantMemoryReplay[F, ReplayCursor, ReplayResult]((trustedRoot, round))(step)
              }
          }
      }
    }

    def replayFromPublicLineage(candidate: GlobalConsensusOutcome): F[Either[String, Unit]] =
      HasherSelector[F].withCurrent { implicit hasher =>
        candidate.finished.certifiedOutcome match {
          case None => "certified_outcome_missing".asLeft[Unit].pure[F]
          case Some(terminalOutcome) =>
            val selectedRoot: F[Either[String, TrustedParent]] =
              latestPublicRecoveryRoot(candidate).flatMap {
                case Left(error)       => error.asLeft[TrustedParent].pure[F]
                case Right(Some(root)) => root.asRight[String].pure[F]
                case Right(None) =>
                  replayRoot(candidate)
              }

            selectedRoot.flatMap {
              case Left(error) => error.asLeft[Unit].pure[F]
              case Right(root) => verifyPublicSegment(root, candidate, terminalOutcome)
            }
        }
      }

    candidate => {
      val active = config.certifiedConsensusActiveAt(candidate.key.value.value)
      val genesisCompatibility =
        CertifiedConsensusGenesis.isRootKey(config.certifiedConsensusActivationKey, candidate.key) &&
          candidate.finished.certifiedOutcome.isEmpty

      if (!active) Async[F].unit
      else if (genesisCompatibility)
        locallyValidatedSnapshot(candidate.key).flatMap {
          case Left(error) =>
            new IllegalStateException(s"downloaded_certified_outcome_genesis:$error").raiseError[F, Unit]
          case Right((snapshot, context)) =>
            validateGenesisRoot(candidate, snapshot, context, seedlistPeerIds).flatMap(
              _.leftMap(error => new IllegalStateException(s"downloaded_certified_outcome_genesis:$error")).liftTo[F]
            )
        }
      else {
        replayFromPublicLineage(candidate).flatMap(
          _.leftMap(error => new IllegalStateException(s"downloaded_certified_outcome_invalid:$error")).liftTo[F]
        )
      }
    }
  }
}
