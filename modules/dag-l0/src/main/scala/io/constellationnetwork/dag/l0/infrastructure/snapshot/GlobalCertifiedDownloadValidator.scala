package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.Duration

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
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

import eu.timepit.refined.auto._

/** Fail-closed trust boundary for a peer-supplied v35 DAG outcome.
  *
  * Outside the explicit permissioned-recovery policy, a QC cannot authenticate the committee declared inside that same QC. A local outcome
  * sidecar also cannot authenticate all of its derived operational fields, so it is never download authority. Every acceptance path begins
  * at one independently checkable public root:
  *
  *   - the locally downloaded/state-proof-validated A-1 snapshot at ordinal-gated activation; or
  *   - the canonical signed first incremental snapshot when certification is active from genesis; or
  *   - the latest explicit recovery epoch, reconstructed from its first successor's ordinary QC and independently validated public parent
  *     under the permissioned seedlist/collateral policy.
  *
  * It then replays the relevant contiguous public artifact/context chain and each child-carried parent certificate through the ordinary
  * production outcome transition. For a later recovery this is only the latest reset-to-tip segment; older recovery epochs are superseded.
  * The terminal certificate comes from the authenticated peer outcome. No prefix is installed until the complete replay equals that
  * terminal outcome.
  *
  * The resulting frozen state is passed to the ordinary `CertifiedConsensus` verifier. No alternate encoder, canonicalizer, hash, QC
  * verifier, or committee rule is introduced. Selected recovery nodes still require the exact env-authorized anchor and all-member barrier;
  * an unconfigured community node accepts only the first successor's quorum-certified, publicly reconstructible boundary—not a standalone
  * synthetic outcome or a single peer assertion.
  */
object GlobalCertifiedDownloadValidator {

  private[snapshot] sealed trait TrustedParentKind
  private[snapshot] object TrustedParentKind {
    case object Certified extends TrustedParentKind
    case object AuthorizedRoot extends TrustedParentKind
  }

  private final case class TrustedParent(
    outcome: GlobalConsensusOutcome,
    kind: TrustedParentKind
  )

  private final case class RoundProjection(
    selected: List[PeerId],
    committee: CertifiedRoundCommitteeProjector.Projection
  )

  private final case class PublicRound(
    key: GlobalSnapshotKey,
    artifact: Signed[GlobalIncrementalSnapshot],
    context: GlobalSnapshotInfo
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
    * that identity even when A-1 belongs to the historical Kryo epoch.
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
    * artifact's signature envelope or permissioned signer set. Keep those checks at this authority boundary as well: a forged or locally
    * corrupted A-1 file must not be able to name the first certified committee.
    */
  private[snapshot] def validateActivationRootArtifact[
    F[_]: Async: Parallel: JsonSerializer: HasherSelector: SecurityProvider
  ](
    expectedOrdinal: SnapshotOrdinal,
    snapshot: Signed[GlobalIncrementalSnapshot],
    context: GlobalSnapshotInfo,
    seedlistPeerIds: Set[PeerId]
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
            _ <- Either.cond(
              seedlistPeerIds.isEmpty || signers.forall(seedlistPeerIds.contains),
              (),
              "activation_artifact_signer_not_seedlisted"
            )
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
    coreCommitteeSize: Int,
    seedlistPeerIds: Set[PeerId],
    allowancePeerIds: Option[Set[PeerId]],
    facilitatorSelector: FacilitatorSelector,
    isContextEligible: (GlobalSnapshotContext, PeerId) => F[Boolean],
    snapshotDownloadStorage: SnapshotDownloadStorage[F],
    stateAdvancer: GlobalSnapshotConsensusStateAdvancer[F]
  )(implicit globalStateProofSelector: GlobalStateProofSelector): GlobalConsensusOutcome => F[Unit] = {

    def recordPublicRecoveryBoundary(outcome: String): F[Unit] =
      Metrics[F]
        .incrementCounter(
          "dag_consensus_certified_recovery_boundary_total",
          Seq(Metrics.unsafeLabelName("outcome") -> outcome)
        )
        .attempt
        .void

    def carried(outcome: GlobalConsensusOutcome): CertifiedRoundCommitteeProjector.CarriedControllerState =
      CertifiedRoundCommitteeProjector.CarriedControllerState(
        activeScores = outcome.activeAdmissionScores.toMap,
        peerQuality = outcome.peerQuality.toMap,
        peerTiers = outcome.peerTiers,
        viewChanges = outcome.peerViewChanges.toMap,
        selfHealth = outcome.peerSelfHealth.toMap
      )

    def projectAuthorizedRoot(
      key: GlobalSnapshotKey,
      trustedParent: GlobalConsensusOutcome
    ): F[Either[String, RoundProjection]] = {
      val seedlistEligible = trustedParent.facilitators.value.filter(pid => seedlistPeerIds.isEmpty || seedlistPeerIds.contains(pid))

      seedlistEligible
        .filterA(isContextEligible(trustedParent.finished.context, _))
        .map { eligible =>
          for {
            _ <- Either.cond(eligible.nonEmpty, (), "trusted_root_eligible_committee_empty")
            _ <- GlobalSnapshotConsensusStateCreator
              .finalizeEligibleCommitteeAtActivation(
                key,
                config.certifiedConsensusActivatesAt(key.value.value),
                eligible,
                eligible.head,
                config.quorumThresholdFraction
              )
              .leftMap(_.getMessage)
            selected = facilitatorSelector.select(eligible, trustedParent.finished.snapshotHash)
            committee = CertifiedRoundCommitteeProjector.project(
              key = key,
              selectedFacilitators = selected,
              recentSigners = trustedParent.recentSigners,
              controllerEvidence = trustedParent.controllerEvidence.getOrElse(SortedMap.empty),
              carried = carried(trustedParent),
              config = config,
              coreCommitteeSize = coreCommitteeSize,
              forcedTier1Peers = Set.empty
            )
            _ <- Either.cond(committee.signingFacilitators.nonEmpty, (), "trusted_root_signing_committee_empty")
            _ <- GlobalSnapshotConsensusStateCreator
              .validateActivationCommittee(
                key,
                config.certifiedConsensusActivatesAt(key.value.value),
                "downloaded activation signing",
                committee.signingFacilitators,
                config.quorumThresholdFraction
              )
              .leftMap(_.getMessage)
          } yield RoundProjection(selected, committee)
        }
    }

    def projectRound(
      key: GlobalSnapshotKey,
      trustedParent: TrustedParent
    ): F[Either[String, RoundProjection]] =
      trustedParent.kind match {
        case TrustedParentKind.Certified =>
          trustedParent.outcome.finished.certifiedOutcome match {
            case Some(certified) =>
              CertifiedRoundCommitteeProjector
                .fromCertifiedParent[F](
                  key = key,
                  parentValue = certified.proposalQc.value,
                  parentRecentSigners = trustedParent.outcome.recentSigners,
                  parentControllerEvidence = trustedParent.outcome.controllerEvidence.getOrElse(SortedMap.empty),
                  parentCarried = carried(trustedParent.outcome),
                  config = config,
                  coreCommitteeSize = coreCommitteeSize,
                  seedlistPeerIds = seedlistPeerIds,
                  isContextEligible = isContextEligible(trustedParent.outcome.finished.context, _),
                  facilitatorSelector = facilitatorSelector,
                  parentArtifactHash = trustedParent.outcome.finished.snapshotHash
                )
                .flatMap(result => result.map(p => RoundProjection(p.nextRound.selectedCommittee, p.committee)).pure[F])
            case None => "trusted_predecessor_certificate_missing".asLeft[RoundProjection].pure[F]
          }

        case TrustedParentKind.AuthorizedRoot => projectAuthorizedRoot(key, trustedParent.outcome)
      }

    def stateFromTrustedParent(
      key: GlobalSnapshotKey,
      trustedParent: TrustedParent
    ): F[Either[String, GlobalSnapshotConsensusState]] =
      projectRound(key, trustedParent).map(
        _.flatMap { projected =>
          val full = projected.committee.signingFacilitators
          val core = projected.committee.committees.core
          val tier1 = projected.committee.committees.tier1
          val controllerInputs = projected.committee.controllerInputs
          val leaderEligibility = LeaderEligibility.fromRecentSigners(
            core = core,
            peerQuality = controllerInputs.peerQuality,
            recentSigners = trustedParent.outcome.recentSigners,
            minParticipationObservations = config.minParticipationObservations,
            minLeaderPoolSize = config.minLeaderPoolSize
          )

          leaderEligibility.leaderPool.headOption
            .toRight("downloaded_round_leader_pool_empty")
            .map { _ =>
              val leader = facilitatorSelector.selectLeaderWeighted(
                leaderEligibility.leaderPool,
                trustedParent.outcome.finished.snapshotHash,
                viewNumber = 0,
                qualityScores = controllerInputs.peerQuality,
                selfHealthHints = controllerInputs.selfHealth,
                peerViewChanges = controllerInputs.viewChanges,
                minLeaderRatioPct = config.leaderRotationMinRatioPct,
                hardLeaderQualityScorePct = config.hardLeaderQualityScorePct,
                minLeaderPoolSize = config.minLeaderPoolSize
              )

              ConsensusState[GlobalSnapshotKey, GlobalSnapshotStatus, GlobalConsensusOutcome, GlobalConsensusKind](
                key = key,
                lastOutcome = trustedParent.outcome,
                facilitators = Facilitators(full),
                roundStartFacilitators = Facilitators(full),
                status = CollectingFacilities(
                  maybeTrigger = None,
                  facilitatorsHash = trustedParent.outcome.finished.facilitatorsHash,
                  lastSnapshotHash = trustedParent.outcome.finished.snapshotHash
                ),
                createdAt = Duration.Zero,
                eligibleFacilitators = EligibleFacilitators(projected.selected),
                coreFacilitators = CoreFacilitators(core),
                tier1Facilitators = Tier1Facilitators(tier1),
                leader = leader,
                initialViewNumber = 0,
                viewNumber = 0,
                entropy = trustedParent.outcome.finished.snapshotHash,
                certifiedConsensusActive = true
              )
            }
        }
      )

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
              validateActivationRootArtifact(parentOrdinal, snapshot, context, seedlistPeerIds).flatMap(_.traverse { _ =>
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
                      .resetLegacyOutcome[F](activationKey, legacy, config.quorumThresholdFraction)
                  )
                } yield TrustedParent(reset, TrustedParentKind.AuthorizedRoot)
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
            } yield validation.as(TrustedParent(root, TrustedParentKind.AuthorizedRoot))
          }
      }

    def replayRoot(candidate: GlobalConsensusOutcome): F[Either[String, TrustedParent]] =
      if (CertifiedConsensusGenesis.isActiveFromGenesis(config.certifiedConsensusActivationKey)) canonicalGenesisRoot
      else exactActivationParent(candidate)

    def loadPublicRound(
      ordinal: SnapshotOrdinal,
      candidate: GlobalConsensusOutcome
    ): F[Either[String, PublicRound]] =
      locallyValidatedSnapshot(ordinal).map(
        _.leftMap(error => s"certified_public_round_missing:$error").flatMap {
          case (locallyValidatedArtifact, context) =>
            val isTerminal = ordinal === candidate.key
            val artifact =
              if (isTerminal) candidate.finished.signedMajorityArtifact
              else locallyValidatedArtifact

            for {
              _ <- Either.cond(locallyValidatedArtifact.value.ordinal === ordinal, (), "artifact_ordinal_mismatch")
              _ <- Either.cond(
                !isTerminal || locallyValidatedArtifact.value === candidate.finished.signedMajorityArtifact.value,
                (),
                "terminal_artifact_value_not_locally_validated"
              )
              _ <- Either.cond(
                !isTerminal || context === candidate.finished.context,
                (),
                "terminal_context_not_locally_validated"
              )
            } yield PublicRound(ordinal, artifact, context)
        }
      )

    def loadPublicRounds(
      startExclusive: GlobalSnapshotKey,
      candidate: GlobalConsensusOutcome
    ): F[Either[String, List[PublicRound]]] = {
      val terminal = BigInt(candidate.key.value.value)

      def loop(next: BigInt, acc: List[PublicRound]): F[Either[String, List[PublicRound]]] =
        if (next > terminal) acc.reverse.asRight[String].pure[F]
        else if (next > BigInt(Long.MaxValue)) "certified_lineage_key_overflow".asLeft[List[PublicRound]].pure[F]
        else {
          val ordinal = SnapshotOrdinal.unsafeApply(next.toLong)
          loadPublicRound(ordinal, candidate).flatMap {
            case Left(error)  => error.asLeft[List[PublicRound]].pure[F]
            case Right(round) => loop(next + 1, round :: acc)
          }
        }

      loop(BigInt(startExclusive.value.value) + 1, List.empty)
    }

    def advancePublicRound(
      trusted: TrustedParent,
      round: PublicRound,
      authority: CertifiedConsensus.CertifiedLineageEvidenceV1
    ): F[Either[String, TrustedParent]] =
      (round.artifact.value.certifiedLineage.flatMap(_.parentLayerEvidence), authority.parentLayerEvidence) match {
        case (Some(_), _)    => "dag_carried_parent_layer_evidence_present".asLeft[TrustedParent].pure[F]
        case (None, Some(_)) => "dag_lineage_layer_evidence_present".asLeft[TrustedParent].pure[F]
        case (None, None) =>
          stateFromTrustedParent(round.key, trusted).flatMap {
            case Left(error) => error.asLeft[TrustedParent].pure[F]
            case Right(state) =>
              stateAdvancer
                .deriveCertifiedPublicRound(state, round.artifact, round.context, authority.parentOutcome)
                .map(_.map { case (_, outcome) => TrustedParent(outcome, TrustedParentKind.Certified) })
          }
      }

    /** Reconstruct one publicly provable recovery-reset root.
      *
      * `round` is the first certified child after an env-authorized synthetic root. It carries no parent certificate because the root is
      * intentionally uncertified. Authority instead comes from the ordinary certificate for `round`, carried by its child or by the
      * authenticated terminal outcome. That certificate binds the exact parent hash and frozen committee. The normal public transition then
      * verifies the certificate signatures, artifact proofs, eligibility, committee projection, and every derived field.
      *
      * This is deliberately self-authenticating permissioned recovery authority, not a second operator artifact: an ordinary certificate
      * quorum from a seedlisted, collateral-eligible committee of at least three members must certify the same reset child. For a
      * three-member committee at the 2/3 policy this means two signatures, not all three. Misuse by a quorum of those allowlisted operators
      * remains attributable under the network's permissioned trust model.
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
          CommitteeViability.canProveNextSeat(committee.size, config.quorumThresholdFraction),
          (),
          s"recovery_seed_boundary_no_next_seat_headroom:${committee.size}"
        )
        _ <- Either.cond(
          config.facilitatorSelectionMax.forall(committee.size <= _),
          (),
          s"recovery_seed_boundary_committee_too_large:${committee.size}"
        )
        _ <- Either.cond(
          seedlistPeerIds.nonEmpty,
          (),
          "recovery_seed_boundary_seedlist_unavailable"
        )
        _ <- Either.cond(
          committee.forall(seedlistPeerIds.contains),
          (),
          "recovery_seed_boundary_member_not_seedlisted"
        )
        _ <- Either.cond(
          allowancePeerIds.forall(allowance => committee.forall(allowance.contains)),
          (),
          "recovery_seed_boundary_member_not_allowed"
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
          locallyValidatedSnapshot(parentOrdinal).flatMap {
            case Left(error) => s"recovery_seed_boundary_parent_missing:$error".asLeft[TrustedParent].pure[F]
            case Right((snapshot, context)) =>
              HasherSelector[F].withCurrent { implicit hasher =>
                GlobalSnapshotArtifactHasher.currentHash[F](snapshot.value).map { snapshotHash =>
                  for {
                    _ <- Either.cond(
                      snapshotHash === value.parentArtifactHash,
                      (),
                      "recovery_seed_boundary_parent_hash_mismatch"
                    )
                    root = GlobalRecoverySeedOutcome.seed(snapshot, context, snapshotHash, committee)
                  } yield TrustedParent(root, TrustedParentKind.AuthorizedRoot)
                }
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
    def latestPublicRecoverySegment(
      candidate: GlobalConsensusOutcome
    ): F[Either[String, Option[(TrustedParent, List[PublicRound])]]] = {
      val firstOrdinaryReplayKey =
        if (CertifiedConsensusGenesis.isActiveFromGenesis(config.certifiedConsensusActivationKey))
          CertifiedConsensusGenesis.FirstIncrementalOrdinal.value.value + 1L
        else config.certifiedConsensusActivationKey

      def loop(
        current: Long,
        child: Option[PublicRound],
        ascending: List[PublicRound]
      ): F[Either[String, Option[(TrustedParent, List[PublicRound])]]] =
        if (current < firstOrdinaryReplayKey) none[(TrustedParent, List[PublicRound])].asRight[String].pure[F]
        else {
          val ordinal = SnapshotOrdinal.unsafeApply(current)
          loadPublicRound(ordinal, candidate).flatMap {
            case Left(error) => error.asLeft[Option[(TrustedParent, List[PublicRound])]].pure[F]
            case Right(round) =>
              val replayRounds = round :: ascending
              val isLaterReset = current > firstOrdinaryReplayKey && round.artifact.value.certifiedLineage.isEmpty

              if (isLaterReset) {
                val authority = child
                  .flatMap(_.artifact.value.certifiedLineage.map(_.parentOutcome))
                  .orElse(Option.when(ordinal === candidate.key)(candidate.finished.certifiedOutcome).flatten)

                recordPublicRecoveryBoundary("detected") >>
                  (authority.toRight(s"recovery_seed_boundary_certificate_missing:$current") match {
                    case Left(error) =>
                      recordPublicRecoveryBoundary("rejected") >>
                        error.asLeft[Option[(TrustedParent, List[PublicRound])]].pure[F]
                    case Right(certified) =>
                      publicRecoveryRoot(round, certified).flatMap {
                        case Left(error) =>
                          recordPublicRecoveryBoundary("rejected") >>
                            error.asLeft[Option[(TrustedParent, List[PublicRound])]].pure[F]
                        case Right(root) =>
                          recordPublicRecoveryBoundary("root_reconstructed") >>
                            (root -> replayRounds).some.asRight[String].pure[F]
                      }
                  })
              } else if (current === firstOrdinaryReplayKey)
                none[(TrustedParent, List[PublicRound])].asRight[String].pure[F]
              else loop(current - 1L, round.some, replayRounds)
          }
        }

      loop(candidate.key.value.value, none, List.empty)
    }

    def replayFromPublicLineage(candidate: GlobalConsensusOutcome): F[Either[String, Unit]] =
      HasherSelector[F].withCurrent { implicit hasher =>
        candidate.finished.certifiedOutcome match {
          case None => "certified_outcome_missing".asLeft[Unit].pure[F]
          case Some(terminalOutcome) =>
            val selectedSegment: F[Either[String, (TrustedParent, List[PublicRound])]] =
              latestPublicRecoverySegment(candidate).flatMap {
                case Left(error)          => error.asLeft[(TrustedParent, List[PublicRound])].pure[F]
                case Right(Some(segment)) => segment.asRight[String].pure[F]
                case Right(None) =>
                  replayRoot(candidate).flatMap {
                    case Left(error) => error.asLeft[(TrustedParent, List[PublicRound])].pure[F]
                    case Right(root) => loadPublicRounds(root.outcome.key, candidate).map(_.map(root -> _))
                  }
              }

            selectedSegment.flatMap {
              case Left(error) => error.asLeft[Unit].pure[F]
              case Right((root, replayRounds)) =>
                val terminalEvidence = CertifiedConsensus.CertifiedLineageEvidenceV1(terminalOutcome, None)
                CertifiedConsensus
                  .verifySequentialLineage[F, TrustedParent, PublicRound](
                    trustedRoot = root,
                    trustedRootKey = root.outcome.key.value.value,
                    frames = replayRounds,
                    terminalEvidence = terminalEvidence.some,
                    domain = CertifiedConsensus.ConsensusDomain.DagL0,
                    configuredFraction = config.quorumThresholdFraction,
                    keyOf = _.key.value.value,
                    lineageOf = _.artifact.value.certifiedLineage,
                    certifiedOutcomeOf = _.outcome.finished.certifiedOutcome
                  )(advancePublicRound)
                  .map(
                    _.flatMap(
                      _.lastOption
                        .toRight("certified_lineage_replay_empty")
                        .flatMap(derived => Either.cond(derived.outcome === candidate, (), "certified_outcome_derivation_mismatch"))
                    )
                  )
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
