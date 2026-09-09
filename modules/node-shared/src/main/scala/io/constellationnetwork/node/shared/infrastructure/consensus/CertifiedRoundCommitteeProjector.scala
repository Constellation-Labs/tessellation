package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Monad
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus.ProposalValue
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ControllerEvidenceEntry, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash

/** Pure, shared projection of the Core/Tier-1 signing committees for one round.
  *
  * Live state creation keeps the controller classification, admission cadence, retained-signing lease, and [[CommitteeBuilder]] partition
  * in one place so proposal construction and validation cannot drift across call sites.
  */
object CertifiedRoundCommitteeProjector {

  /** Buffered withdrawal rumors are not certified and can arrive before state creation on one node but after it on another.
    *
    * Legacy behavior is retained before v35. In the certified epoch, the frozen round-start/QC committee ignores that local timing input;
    * an in-round withdrawal may suppress local work but cannot change the committee. A future lease-withdrawal feature needs an explicit
    * certified transition.
    */
  def roundStartWithdrawals(certifiedConsensusActive: Boolean, locallyObserved: Set[PeerId]): Set[PeerId] =
    if (certifiedConsensusActive) Set.empty else locallyObserved

  final case class CarriedControllerState(
    activeScores: Map[PeerId, Int],
    peerQuality: Map[PeerId, (Int, Int)],
    peerTiers: SortedMap[PeerId, Int],
    viewChanges: Map[PeerId, Long],
    selfHealth: Map[PeerId, SelfHealthHint]
  )

  object CarriedControllerState {
    val empty: CarriedControllerState =
      CarriedControllerState(Map.empty, Map.empty, SortedMap.empty, Map.empty, Map.empty)
  }

  final case class Projection(
    controllerInputs: ControllerEvidenceDerivation.ControllerInputs,
    admissionSizing: ConsensusPeerController.AdmissionSizing,
    expansionAllowed: Boolean,
    maxExpansionThisRound: Int,
    activeAdmission: ActiveFacilitatorAdmission.Result,
    signingMembership: ConsensusPeerController.SigningMembership,
    committees: CommitteeBuilder.Committees,
    signingFacilitators: List[PeerId]
  )

  /** Complete next-round projection from an independently trusted certified parent.
    *
    * This is the single composition used by live state creation and downloaded-outcome reconstruction. The parent QC supplies the certified
    * membership delta; the existing next-round projector applies seedlist/collateral/selector eligibility; and [[project]] derives the
    * Core/Tier-1 split from the parent evidence window. Keeping the composition here prevents recovery from growing a second committee
    * algorithm.
    */
  final case class FromCertifiedParent(
    nextRound: CertifiedNextRoundProjector.Projection,
    committee: Projection
  )

  /** Construct a live round from the parent QC's exact continuation authority.
    *
    * Current controller policy still computes diagnostics, admission sizing, and candidate classification, but it is not allowed to replace
    * the already-certified full/Core sets at state creation. This is what permits a coordinated policy upgrade: the old policy's final QC
    * fixes the first authority seen by the new release; the new policy governs only the continuation proposed from that round.
    */
  def fromCertifiedAuthority(
    key: SnapshotOrdinal,
    authority: CertifiedConsensus.CertifiedRoundAuthorityV1,
    parentRecentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    parentControllerEvidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry],
    parentCarried: CarriedControllerState,
    config: ConsensusConfig,
    coreCommitteeSize: Int
  ): Projection = {
    val full = authority.facilitators.toSortedSet.toList
    val core = authority.core.toSortedSet.toList
    val coreSet = core.toSet
    val tier1 = full.filterNot(coreSet.contains)
    val classified = project(
      key = key,
      selectedFacilitators = full,
      recentSigners = parentRecentSigners,
      controllerEvidence = parentControllerEvidence,
      carried = parentCarried,
      config = config,
      coreCommitteeSize = coreCommitteeSize,
      forcedTier1Peers = tier1.toSet
    )
    val authoritativeCommittees = CommitteeBuilder.Committees(
      core = core,
      tier1 = tier1,
      witness = List.empty,
      effectiveTiers = SortedMap.from(
        core.iterator.map(_ -> TierTransitions.Core) ++ tier1.iterator.map(_ -> TierTransitions.Tier1)
      ),
      chronicExcluded = List.empty,
      chronicReplacements = List.empty,
      chronicReadmitted = List.empty
    )

    classified.copy(
      signingMembership = ConsensusPeerController.SigningMembership(full, tier1.toSet),
      committees = authoritativeCommittees,
      signingFacilitators = full
    )
  }

  def fromCertifiedParent[F[_]: Monad](
    key: SnapshotOrdinal,
    parentValue: ProposalValue,
    parentRecentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    parentControllerEvidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry],
    parentCarried: CarriedControllerState,
    config: ConsensusConfig,
    coreCommitteeSize: Int,
    seedlistPeerIds: Set[PeerId],
    isContextEligible: PeerId => F[Boolean],
    facilitatorSelector: FacilitatorSelector,
    parentArtifactHash: Hash
  ): F[Either[String, FromCertifiedParent]] =
    CertifiedNextRoundProjector
      .project[F](
        parentValue.roundStartFacilitators.toSortedSet.toList,
        parentValue.admittedPeers.toSet,
        parentValue.evictedPeers.toSet,
        config.activeAdmissionMaxExpansionPerRound,
        seedlistPeerIds,
        isContextEligible,
        facilitatorSelector,
        parentArtifactHash
      )
      .map(
        _.map(
          finishCertifiedParentProjection(
            key,
            parentValue,
            parentRecentSigners,
            parentControllerEvidence,
            parentCarried,
            config,
            coreCommitteeSize,
            _
          )
        )
      )

  private def finishCertifiedParentProjection(
    key: SnapshotOrdinal,
    parentValue: ProposalValue,
    parentRecentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    parentControllerEvidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry],
    parentCarried: CarriedControllerState,
    config: ConsensusConfig,
    coreCommitteeSize: Int,
    nextRound: CertifiedNextRoundProjector.Projection
  ): FromCertifiedParent = {
    val committee = project(
      key = key,
      selectedFacilitators = nextRound.selectedCommittee,
      recentSigners = parentRecentSigners,
      controllerEvidence = parentControllerEvidence,
      carried = parentCarried,
      config = config,
      coreCommitteeSize = coreCommitteeSize,
      forcedTier1Peers = parentValue.admittedPeers.toSet
    )

    FromCertifiedParent(nextRound, committee)
  }

  def project(
    key: SnapshotOrdinal,
    selectedFacilitators: List[PeerId],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    controllerEvidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry],
    carried: CarriedControllerState,
    config: ConsensusConfig,
    coreCommitteeSize: Int,
    forcedTier1Peers: Set[PeerId],
    withdrawnPeers: Set[PeerId] = Set.empty
  ): Projection = {
    val controllerInputs = ControllerEvidenceDerivation.controllerInputsWithFallback(
      evidence = controllerEvidence,
      carriedScores = carried.activeScores,
      carriedQuality = carried.peerQuality,
      carriedTiers = carried.peerTiers,
      carriedViewChanges = carried.viewChanges,
      carriedSelfHealth = carried.selfHealth
    )
    val admissionSizing = ConsensusPeerController.AdmissionSizing.from(
      config,
      coreCommitteeSize,
      selectedFacilitators.size
    )
    val expansionAllowed = ActiveFacilitatorAdmission.expansionAllowedAtOrdinal(
      key.value.value,
      config.activeAdmissionExpansionIntervalRounds
    )
    val maxExpansionThisRound =
      if (expansionAllowed) config.activeAdmissionMaxExpansionPerRound else 0
    val activeAdmission = ConsensusPeerController.chooseActive(
      ConsensusPeerController.AdmissionInput(
        selected = selectedFacilitators,
        recentSigners = recentSigners,
        latestRoundStartFacilitators = controllerEvidence.lastOption
          .map(_._2.roundStartFacilitators.toSet)
          .getOrElse(Set.empty),
        peerQuality = controllerInputs.peerQuality,
        activeScores = controllerInputs.activeScores,
        sizing = admissionSizing,
        minParticipationObservations = config.minParticipationObservations,
        minParticipationRatio = config.minParticipationRatio,
        config = ConsensusPeerController.Config(
          promoteThreshold = config.activeAdmissionPromoteThreshold,
          retainThreshold = config.activeAdmissionRetainThreshold,
          demoteThreshold = config.activeAdmissionDemoteThreshold,
          maxScore = config.activeAdmissionMaxScore,
          signatureReward = config.activeAdmissionSignatureReward,
          responderReward = config.activeAdmissionResponderReward,
          missedActivePenalty = config.activeAdmissionMissedActivePenalty,
          timeoutMissingPenalty = config.activeAdmissionTimeoutMissingPenalty,
          evictedPenalty = config.activeAdmissionEvictedPenalty,
          degradedPenalty = config.activeAdmissionDegradedPenalty,
          criticalPenalty = config.activeAdmissionCriticalPenalty,
          passiveDecay = config.activeAdmissionPassiveDecay,
          maxExpansionPerRound = maxExpansionThisRound,
          minProbationReentrySlots = config.activeAdmissionMinProbationReentrySlots,
          recentSignerWindow = config.activeAdmissionRecentSignerWindow
        )
      )
    )
    val signingMembership = ConsensusPeerController.retainSelectedForSigning(selectedFacilitators, activeAdmission)
    val active = signingMembership.retained.filterNot(withdrawnPeers.contains)
    val committees = CommitteeBuilder.build(
      candidates = active,
      priorTiers = controllerInputs.peerTiers,
      peerQuality = controllerInputs.peerQuality,
      coreFloor = coreCommitteeSize,
      minObservations = config.minParticipationObservations,
      minRatio = config.minParticipationRatio,
      nonCorePeers = signingMembership.nonCore.intersect(active.toSet),
      forcedTier1Peers = forcedTier1Peers.intersect(active.toSet),
      chronicMisses = controllerInputs.chronicMisses,
      activeScores = controllerInputs.activeScores
    )
    val signingSet = committees.core.toSet ++ committees.tier1.toSet

    Projection(
      controllerInputs,
      admissionSizing,
      expansionAllowed,
      maxExpansionThisRound,
      activeAdmission,
      signingMembership,
      committees,
      active.filter(signingSet.contains)
    )
  }
}
