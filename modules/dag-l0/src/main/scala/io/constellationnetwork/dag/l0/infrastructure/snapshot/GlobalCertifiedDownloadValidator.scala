package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.Duration

import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.dag.l0.infrastructure.snapshot.schema._
import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.OrdinalJsonSidecarStorage
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{SnapshotOrdinal, _}
import io.constellationnetwork.security.HasherSelector
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

/** Fail-closed trust boundary for a peer-supplied v35 DAG outcome.
  *
  * A QC cannot authenticate the committee declared inside that same QC. Download therefore starts from one of two independent authorities
  * only:
  *
  *   - at the exact activation key, the locally downloaded/state-proof-validated A-1 snapshot and its signed controller evidence; or
  *   - after activation, a predecessor sidecar that this node previously produced or accepted through this validator, tied back to the
  *     locally validated public snapshot.
  *
  * The resulting frozen state is passed to the ordinary [[CertifiedConsensus]] adoption verifier through `certifiedOutcomeAdoption`. No
  * alternate encoder, canonicalizer, hash, QC verifier, or committee rule is introduced. A signed operator recovery-plan anchor is
  * validated by its existing preflight and intentionally bypasses this QC path.
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

  def make[F[_]: Async: HasherSelector](
    config: ConsensusConfig,
    coreCommitteeSize: Int,
    seedlistPeerIds: Set[PeerId],
    facilitatorSelector: FacilitatorSelector,
    consensusFns: GlobalSnapshotConsensusFunctions[F],
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
        .filterA(consensusFns.facilitatorEligible(trustedParent.finished.context, _))
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
                  isContextEligible = consensusFns.facilitatorEligible(trustedParent.outcome.finished.context, _),
                  facilitatorSelector = facilitatorSelector,
                  parentArtifactHash = trustedParent.outcome.finished.snapshotHash
                )
                .map(_.map(p => RoundProjection(p.nextRound.selectedCommittee, p.committee)))
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
      HasherSelector[F].forOrdinal(ordinal) { implicit hasher =>
        snapshotDownloadStorage
          .readCombinedValidated(ordinal)
          .map(_.toRight(s"trusted_snapshot_missing:${ordinal.value.value}"))
      }

    def exactActivationParent(candidate: GlobalConsensusOutcome): F[Either[String, TrustedParent]] = {
      val activation = candidate.key.value.value

      if (activation <= 0L)
        "activation_parent_unavailable_at_genesis".asLeft[TrustedParent].pure[F]
      else {
        val parentOrdinal = SnapshotOrdinal.unsafeApply(activation - 1L)

        locallyValidatedSnapshot(parentOrdinal).flatMap(_.traverse {
          case (snapshot, context) =>
            for {
              hashed <- HasherSelector[F].forOrdinal(parentOrdinal)(implicit hasher => snapshot.toHashed[F])
              proofSigners = snapshot.proofs.toSortedSet.toList.map(_.id.toPeerId)
              legacy = GlobalConsensusOutcome(
                key = parentOrdinal,
                facilitators = Facilitators(proofSigners),
                removedFacilitators = RemovedFacilitators.empty,
                withdrawnFacilitators = WithdrawnFacilitators.empty,
                eligibleFacilitators = EligibleFacilitators(proofSigners),
                finished = Finished(
                  snapshot,
                  context,
                  EventTrigger,
                  Candidates.empty,
                  Hash.empty,
                  hashed.hash
                )
              )
              reset <- HasherSelector[F].withCurrent(implicit hasher =>
                GlobalSnapshotConsensusStateCreator
                  .resetLegacyOutcome[F](candidate.key, legacy, config.quorumThresholdFraction)
              )
            } yield TrustedParent(reset, TrustedParentKind.AuthorizedRoot)
        })
      }
    }

    def trustedLocalParent(candidate: GlobalConsensusOutcome): F[Either[String, TrustedParent]] = {
      val parentOrdinal = SnapshotOrdinal.unsafeApply(candidate.key.value.value - 1L)

      (certifiedOutcomeSidecar.read(parentOrdinal), locallyValidatedSnapshot(parentOrdinal)).tupled.flatMap {
        case (Some(parent), Right((snapshot, context))) =>
          HasherSelector[F].forOrdinal(parentOrdinal) { implicit hasher =>
            snapshot.toHashed[F].map { hashed =>
              for {
                _ <- validatePredecessorBindings(
                  parent.key === parentOrdinal,
                  parent.finished.signedMajorityArtifact === snapshot,
                  parent.finished.context === context,
                  parent.finished.snapshotHash === hashed.hash
                )
                kind <- trustedParentKind(parent)
              } yield TrustedParent(parent, kind)
            }
          }
        case (None, _)        => "trusted_predecessor_sidecar_missing".asLeft[TrustedParent].pure[F]
        case (_, Left(error)) => error.asLeft[TrustedParent].pure[F]
      }
    }

    candidate => {
      val active = config.certifiedConsensusActiveAt(candidate.key.value.value)
      val genesisCompatibility = candidate.key.value.value == 0L && candidate.finished.certifiedOutcome.isEmpty

      if (!active || genesisCompatibility) Async[F].unit
      else {
        val trustedParent =
          if (config.certifiedConsensusActivatesAt(candidate.key.value.value)) exactActivationParent(candidate)
          else trustedLocalParent(candidate)

        for {
          parent <- trustedParent.flatMap(
            _.leftMap(error => new IllegalStateException(s"downloaded_certified_outcome_parent:$error")).liftTo[F]
          )
          state <- stateFromTrustedParent(candidate.key, parent).flatMap(
            _.leftMap(error => new IllegalStateException(s"downloaded_certified_outcome_state:$error")).liftTo[F]
          )
          adoption <- stateAdvancer.certifiedOutcomeAdoption(state, candidate)
          _ <- adoption.leftMap(error => new IllegalStateException(s"downloaded_certified_outcome_invalid:$error")).liftTo[F]
        } yield ()
      }
    }
  }
}
