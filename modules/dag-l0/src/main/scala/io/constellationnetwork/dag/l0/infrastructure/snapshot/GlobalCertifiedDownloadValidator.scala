package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.Duration

import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.OrdinalJsonSidecarStorage
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{SnapshotOrdinal, _}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{HasherSelector, SecurityProvider}

/** Fail-closed trust boundary for a peer-supplied v35 DAG outcome.
  *
  * A QC cannot authenticate the committee declared inside that same QC. The fast path therefore accepts only an exact predecessor sidecar
  * that this node previously produced or validated and binds it to the locally validated public predecessor. If that cache is absent or
  * corrupt, the authority path begins at one independent public root:
  *
  *   - the locally downloaded/state-proof-validated A-1 snapshot at ordinal-gated activation; or
  *   - the canonical signed first incremental snapshot when certification is active from genesis.
  *
  * It then replays the complete contiguous public artifact/context chain and each child-carried parent certificate through the ordinary
  * production outcome transition. The terminal certificate comes from the authenticated peer outcome. No prefix is installed until the
  * complete replay equals that terminal outcome.
  *
  * The resulting frozen state is passed to the ordinary `CertifiedConsensus` verifier. No alternate encoder, canonicalizer, hash, QC
  * verifier, or committee rule is introduced. An operator-authorized recovery anchor remains governed by its existing exact-anchor
  * preflight and is not inferred from a standalone peer QC.
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

  private[snapshot] def trustedParentKind(outcome: GlobalConsensusOutcome): Either[String, TrustedParentKind] =
    if (outcome.finished.certifiedOutcome.nonEmpty) TrustedParentKind.Certified.asRight
    else if (GlobalRecoveryPlanOutcome.isCanonicalRoot(outcome)) TrustedParentKind.AuthorizedRoot.asRight
    else "trusted_predecessor_not_certified_or_authorized_root".asLeft

  private[snapshot] def validatePredecessorBindings(
    keyMatches: Boolean,
    artifactMatches: Boolean,
    contextMatches: Boolean,
    hashMatches: Boolean
  ): Either[String, Unit] =
    for {
      _ <- Either.cond(keyMatches, (), "trusted_predecessor_key_mismatch")
      _ <- Either.cond(artifactMatches, (), "trusted_predecessor_artifact_mismatch")
      _ <- Either.cond(contextMatches, (), "trusted_predecessor_context_mismatch")
      _ <- Either.cond(hashMatches, (), "trusted_predecessor_hash_mismatch")
    } yield ()

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
        expected = GlobalRecoveryPlanOutcome.seed(
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

  def make[F[_]: Async: Parallel: JsonSerializer: HasherSelector: SecurityProvider](
    config: ConsensusConfig,
    coreCommitteeSize: Int,
    seedlistPeerIds: Set[PeerId],
    facilitatorSelector: FacilitatorSelector,
    isContextEligible: (GlobalSnapshotContext, PeerId) => F[Boolean],
    snapshotDownloadStorage: SnapshotDownloadStorage[F],
    certifiedOutcomeSidecar: OrdinalJsonSidecarStorage[F, GlobalConsensusOutcome],
    stateAdvancer: GlobalSnapshotConsensusStateAdvancer[F]
  )(implicit globalStateProofSelector: GlobalStateProofSelector): GlobalConsensusOutcome => F[Unit] = {

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

          locallyValidatedSnapshot(parentOrdinal).flatMap(_.traverse {
            case (snapshot, context) =>
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

    def canonicalGenesisRoot: F[Either[String, TrustedParent]] =
      locallyValidatedSnapshot(CertifiedConsensusGenesis.FirstIncrementalOrdinal).flatMap {
        case Left(error) => error.asLeft[TrustedParent].pure[F]
        case Right((snapshot, context)) =>
          HasherSelector[F].withCurrent { implicit hasher =>
            for {
              snapshotHash <- GlobalSnapshotArtifactHasher.currentHash[F](snapshot.value)
              committee = SortedSet.from(snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId))
              root = GlobalRecoveryPlanOutcome.seed(snapshot, context, snapshotHash, committee)
              validation <- validateGenesisRoot(root, snapshot, context, seedlistPeerIds)
            } yield validation.as(TrustedParent(root, TrustedParentKind.AuthorizedRoot))
          }
      }

    def replayRoot(candidate: GlobalConsensusOutcome): F[Either[String, TrustedParent]] =
      if (CertifiedConsensusGenesis.isActiveFromGenesis(config.certifiedConsensusActivationKey)) canonicalGenesisRoot
      else exactActivationParent(candidate)

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
          locallyValidatedSnapshot(ordinal).flatMap {
            case Left(error) => s"certified_public_round_missing:$error".asLeft[List[PublicRound]].pure[F]
            case Right((artifact, context)) =>
              val bindings = for {
                _ <- Either.cond(artifact.value.ordinal === ordinal, (), "artifact_ordinal_mismatch")
                _ <- Either.cond(
                  if (ordinal === candidate.key) Signed.sameValueAndProofs(artifact, candidate.finished.signedMajorityArtifact) else true,
                  (),
                  "terminal_artifact_not_locally_validated"
                )
                _ <- Either.cond(
                  if (ordinal === candidate.key) context === candidate.finished.context else true,
                  (),
                  "terminal_context_not_locally_validated"
                )
              } yield ()

              bindings match {
                case Left(error) => error.asLeft[List[PublicRound]].pure[F]
                case Right(_)    => loop(next + 1, PublicRound(ordinal, artifact, context) :: acc)
              }
          }
        }

      loop(BigInt(startExclusive.value.value) + 1, List.empty)
    }

    def advancePublicRound(
      trusted: GlobalConsensusOutcome,
      round: PublicRound,
      authority: CertifiedConsensus.CertifiedLineageEvidenceV1
    ): F[Either[String, GlobalConsensusOutcome]] =
      (round.artifact.value.certifiedLineage.flatMap(_.parentLayerEvidence), authority.parentLayerEvidence) match {
        case (Some(_), _)    => "dag_carried_parent_layer_evidence_present".asLeft[GlobalConsensusOutcome].pure[F]
        case (None, Some(_)) => "dag_lineage_layer_evidence_present".asLeft[GlobalConsensusOutcome].pure[F]
        case (None, None) =>
          trustedParentKind(trusted) match {
            case Left(error) => error.asLeft[GlobalConsensusOutcome].pure[F]
            case Right(kind) =>
              stateFromTrustedParent(round.key, TrustedParent(trusted, kind)).flatMap {
                case Left(error) => error.asLeft[GlobalConsensusOutcome].pure[F]
                case Right(state) =>
                  stateAdvancer
                    .deriveCertifiedPublicRound(state, round.artifact, round.context, authority.parentOutcome)
                    .map(_.map(_._2))
              }
          }
      }

    def replayFromPublicLineage(candidate: GlobalConsensusOutcome): F[Either[String, Unit]] =
      HasherSelector[F].withCurrent { implicit hasher =>
        candidate.finished.certifiedOutcome match {
          case None => "certified_outcome_missing".asLeft[Unit].pure[F]
          case Some(terminalOutcome) =>
            replayRoot(candidate).flatMap {
              case Left(error) => error.asLeft[Unit].pure[F]
              case Right(root) =>
                loadPublicRounds(root.outcome.key, candidate).flatMap {
                  case Left(error) => error.asLeft[Unit].pure[F]
                  case Right(rounds) =>
                    val terminalEvidence = CertifiedConsensus.CertifiedLineageEvidenceV1(terminalOutcome, None)
                    CertifiedConsensus
                      .verifySequentialLineage[F, GlobalConsensusOutcome, PublicRound](
                        trustedRoot = root.outcome,
                        trustedRootKey = root.outcome.key.value.value,
                        frames = rounds,
                        terminalEvidence = terminalEvidence.some,
                        domain = CertifiedConsensus.ConsensusDomain.DagL0,
                        configuredFraction = config.quorumThresholdFraction,
                        keyOf = _.key.value.value,
                        lineageOf = _.artifact.value.certifiedLineage,
                        certifiedOutcomeOf = _.finished.certifiedOutcome
                      )(advancePublicRound)
                      .map(
                        _.flatMap(
                          _.lastOption
                            .toRight("certified_lineage_replay_empty")
                            .flatMap(derived => Either.cond(derived === candidate, (), "certified_outcome_derivation_mismatch"))
                        )
                      )
                }
            }
        }
      }

    def trustedLocalParent(candidate: GlobalConsensusOutcome): F[Either[String, TrustedParent]] = {
      val parentOrdinal = SnapshotOrdinal.unsafeApply(candidate.key.value.value - 1L)

      certifiedOutcomeSidecar.read(parentOrdinal).flatMap {
        case Some(parent) =>
          trustedParentKind(parent) match {
            case Left(error) => error.asLeft[TrustedParent].pure[F]
            case Right(kind) =>
              val isGenesisRoot = kind match {
                case TrustedParentKind.AuthorizedRoot =>
                  CertifiedConsensusGenesis.isRootKey(config.certifiedConsensusActivationKey, parentOrdinal)
                case TrustedParentKind.Certified => false
              }
              locallyValidatedSnapshot(parentOrdinal).flatMap {
                case Left(error) => error.asLeft[TrustedParent].pure[F]
                case Right((snapshot, context)) =>
                  val rootValidation =
                    if (isGenesisRoot) validateGenesisRoot(parent, snapshot, context, seedlistPeerIds)
                    else ().asRight[String].pure[F]

                  rootValidation.flatMap {
                    case Left(error) => s"trusted_predecessor_genesis_invalid:$error".asLeft[TrustedParent].pure[F]
                    case Right(_) =>
                      val snapshotHash = kind match {
                        case TrustedParentKind.Certified =>
                          HasherSelector[F].withCurrent(implicit hasher => GlobalSnapshotArtifactHasher.currentHash[F](snapshot.value))
                        case TrustedParentKind.AuthorizedRoot if isGenesisRoot =>
                          HasherSelector[F].withCurrent(implicit hasher => GlobalSnapshotArtifactHasher.currentHash[F](snapshot.value))
                        case TrustedParentKind.AuthorizedRoot =>
                          HasherSelector[F].forOrdinal(parentOrdinal)(implicit hasher =>
                            GlobalSnapshotArtifactHasher.historicalHash[F](snapshot.value)
                          )
                      }

                      snapshotHash.map { hash =>
                        validatePredecessorBindings(
                          parent.key === parentOrdinal,
                          if (isGenesisRoot) Signed.sameValueAndProofs(parent.finished.signedMajorityArtifact, snapshot)
                          else parent.finished.signedMajorityArtifact === snapshot,
                          parent.finished.context === context,
                          parent.finished.snapshotHash === hash
                        ).as(TrustedParent(parent, kind))
                      }
                  }
              }
          }
        case None => "trusted_predecessor_sidecar_missing".asLeft[TrustedParent].pure[F]
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
        def validateAgainst(parent: TrustedParent): F[Either[String, Unit]] =
          HasherSelector[F].withCurrent { implicit hasher =>
            CertifiedConsensus
              .verifyCarriedParentOutcome[F](
                candidate.finished.signedMajorityArtifact.value.certifiedLineage,
                parent.outcome.finished.certifiedOutcome,
                CertifiedConsensus.ConsensusDomain.DagL0,
                config.quorumThresholdFraction
              )
              .flatMap {
                case Left(error) => error.asLeft[Unit].pure[F]
                case Right(Some(carried)) if carried.parentLayerEvidence.nonEmpty =>
                  "dag_lineage_layer_evidence_present".asLeft[Unit].pure[F]
                case Right(_) =>
                  stateFromTrustedParent(candidate.key, parent).flatMap {
                    case Left(error)  => error.asLeft[Unit].pure[F]
                    case Right(state) => stateAdvancer.certifiedOutcomeAdoption(state, candidate).map(_.void)
                  }
              }
          }

        // Prefer the exact locally validated predecessor cache. If it is absent or corrupt,
        // replay the canonical public child-carried chain instead of allowing a node-local
        // sidecar availability failure to strand an otherwise valid downloader.
        trustedLocalParent(candidate).flatMap {
          case Right(parent) => validateAgainst(parent)
          case Left(_)       => replayFromPublicLineage(candidate)
        }.flatMap(
          _.leftMap(error => new IllegalStateException(s"downloaded_certified_outcome_invalid:$error")).liftTo[F]
        )
      }
    }
  }
}
