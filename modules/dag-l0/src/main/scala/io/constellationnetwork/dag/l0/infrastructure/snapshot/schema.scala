package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Show
import cats.syntax.show._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import monocle.Lens
import monocle.macros.GenLens

object schema {

  /** Consensus status phases for the Global L0 snapshot consensus state machine.
    *
    * {{{
    * CollectingFacilities → CollectingProposals → CollectingSignatures → Finished
    * }}}
    *
    * Each phase collects peer declarations (facilities, proposals, signatures) until quorum is reached, then advances to the next phase.
    * The `Finished` state carries the signed artifact and context forward as `lastOutcome` for the next consensus round.
    */
  @derive(eqv)
  sealed trait GlobalConsensusStep

  object GlobalConsensusStep {
    implicit val show: Show[GlobalConsensusStep] = Show.show {
      case CollectingFacilities(maybeTrigger, facilitatorsHash, lastSnapshotHash) =>
        s"CollectingFacilities{maybeTrigger=${maybeTrigger.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}"
      case CollectingProposals(
            majorityTrigger,
            proposalArtifactInfo,
            candidates,
            facilitatorsHash,
            lastSnapshotHash,
            _,
            _,
            _
          ) =>
        s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, proposalArtifactInfo=${proposalArtifactInfo.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}}"
      case CollectingSignatures(majorityArtifactInfo, majorityTrigger, candidates, facilitatorsHash, lastSnapshotHash, _, _) =>
        s"CollectingSignatures{majorityArtifactInfo=${majorityArtifactInfo.show}, ${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}}"
      case Finished(_, _, majorityTrigger, candidates, facilitatorsHash, snapshotHash, _) =>
        s"Finished{majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, snapshotHash=${snapshotHash.show}"
    }
  }

  final case class CollectingFacilities(
    maybeTrigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends GlobalConsensusStep

  final case class CollectingProposals(
    majorityTrigger: ConsensusTrigger,
    proposalArtifactInfo: ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext],
    candidates: Candidates,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    // Frozen leader-selected Facility trigger evidence for byte-identical re-spread.
    triggerEvidence: List[Signed[TriggerStatement]] = List.empty,
    // Exact local candidate used if this node is the leader and must re-spread.
    proposedValue: Option[ProposalValue] = None,
    // Leader value already validated by this node. While populated, the state stays in
    // CollectingProposals until a frozen-committee prepare QC is available.
    acceptedValue: Option[ProposalValue] = None
  ) extends GlobalConsensusStep

  final case class CollectingSignatures(
    majorityArtifactInfo: ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext],
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    proposalValue: Option[ProposalValue] = None,
    proposalQc: Option[CertifiedProposalQC] = None
  ) extends GlobalConsensusStep

  @derive(encoder, decoder, eqv)
  final case class Finished(
    signedMajorityArtifact: Signed[GlobalSnapshotArtifact],
    context: GlobalSnapshotContext,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    snapshotHash: Hash,
    certifiedOutcome: Option[CertifiedOutcome] = None
  ) extends GlobalConsensusStep

  /** Outcome of a completed consensus round, carried forward as `lastOutcome` into the next round.
    *
    * `removalPenalties` uses `SortedMap` to ensure deterministic iteration when computing penalty decrements and filtering penalized peers
    * in the next round.
    *
    * `peerQuality` tracks consensus-agreed quality scores: `(roundsCompleted, roundsParticipated)` per peer. Because all nodes in a round
    * agree on the same facilitator list, removals, and withdrawals, these counters are deterministic across the network — enabling
    * quality-weighted leader selection without local score divergence.
    *
    * Decode contract: this derives a circe decoder with `useDefaults = false`, so every NON-`Option` field must be present in the decoded
    * JSON. The Scala default values below (`= SortedMap.empty`, etc.) apply only to in-code construction -- they do NOT make decode
    * tolerant of a missing field. `Option`-typed fields are the exception: a missing key decodes to `None` (e.g. `controllerEvidence`).
    * Decoding an outcome that lacks one of the non-`Option` fields would fail. Coordinated deployments rely on distinct advertised
    * `versionHash` values to keep different release schemas from joining. L0 also requires exact `deterministicConfigHash` equality; the
    * advertised jar field is metadata, not a fence. Before adding the next field, make it `Option`-typed or switch the derivation to
    * `useDefaults`, so historical decode stays safe (see ADR-0016 and ADR-0027).
    */
  @derive(encoder, decoder, eqv)
  final case class GlobalConsensusOutcome(
    key: GlobalSnapshotKey,
    facilitators: Facilitators,
    removedFacilitators: RemovedFacilitators,
    withdrawnFacilitators: WithdrawnFacilitators,
    eligibleFacilitators: EligibleFacilitators,
    finished: Finished,
    // Exponential removal penalties for certificate-applied evictions (rounds remaining).
    removalPenalties: SortedMap[PeerId, Int] = SortedMap.empty,
    cumulativeMissCounts: SortedMap[PeerId, Long] = SortedMap.empty,
    // Canonical committee size per recent round.
    recentCommitteeSizes: SortedMap[SnapshotOrdinal, Int] = SortedMap.empty,
    // B2 sticky probation: peers awaiting a quorum-certified AdmissionCertificate.
    readmissionCountdown: SortedMap[PeerId, Int] = SortedMap.empty,
    lastTimeoutCertificateVoters: SortedSet[PeerId] = SortedSet.empty,
    // View-from-time anchors.
    recentRoundEndTimes: SortedMap[SnapshotOrdinal, Long] = SortedMap.empty,
    // Certificate-anchored absolute penalty horizons.
    penaltyUntil: Option[SortedMap[PeerId, SnapshotOrdinal]] = None,
    expandedBeyondSingleton: Option[Boolean] = None
  ) {

    /** The frozen voting committee of this outcome's round, as certified by its proposal QC. None at the recovery-seed root. */
    def certifiedRoundCommittee: Option[Set[PeerId]] =
      finished.certifiedOutcome.map(_.proposalQc.value.roundStartFacilitators.toSortedSet.toSet)

    def eligibleOrFacilitators: List[PeerId] =
      if (eligibleFacilitators.value.nonEmpty) eligibleFacilitators.value
      else facilitators.value

    def toOperationalState: ConsensusOperationalState =
      GlobalConsensusOutcome.operationalState(
        removalPenalties,
        cumulativeMissCounts,
        recentCommitteeSizes,
        readmissionCountdown,
        recentRoundEndTimes,
        penaltyUntil
      )

    /** The deterministic, chain-derived subset packed into the next artifact's signed `peerHistory`. Per-peer bookkeeping is node-local and
      * stays out of signed bytes.
      */
    def signedArtifactPeerHistory: ConsensusOperationalState =
      ConsensusOperationalState(
        perPeer = SortedMap.empty,
        recentCommitteeSizes = recentCommitteeSizes,
        penaltyUntil = penaltyUntil.filter(_.nonEmpty)
      )
  }

  @derive(encoder, decoder, eqv, show)
  sealed trait GlobalConsensusKind

  object GlobalConsensusKind {
    case object Facility extends GlobalConsensusKind

    case object Proposal extends GlobalConsensusKind

    case object Signature extends GlobalConsensusKind
  }

  object GlobalConsensusOutcome {

    /** Pack the post-round operational state once for both the persisted outcome and the certified commitment. */
    def operationalState(
      removalPenalties: SortedMap[PeerId, Int],
      cumulativeMissCounts: SortedMap[PeerId, Long],
      recentCommitteeSizes: SortedMap[SnapshotOrdinal, Int],
      readmissionCountdown: SortedMap[PeerId, Int],
      recentRoundEndTimes: SortedMap[SnapshotOrdinal, Long],
      penaltyUntil: Option[SortedMap[PeerId, SnapshotOrdinal]]
    ): ConsensusOperationalState = {
      val keys: Set[PeerId] = removalPenalties.keySet | cumulativeMissCounts.keySet | readmissionCountdown.keySet
      val perPeer: SortedMap[PeerId, PerPeerOperationalRecord] =
        SortedMap.from(
          keys.iterator.map { pid =>
            pid -> PerPeerOperationalRecord(
              removalPenalty = removalPenalties.getOrElse(pid, 0),
              cumulativeMissCount = cumulativeMissCounts.getOrElse(pid, 0L),
              readmissionCountdown = ReadmissionMaintenance.persistenceValue(readmissionCountdown, pid)
            )
          }
        )
      ConsensusOperationalState(
        perPeer = perPeer,
        recentCommitteeSizes = recentCommitteeSizes,
        recentRoundEndTimes = if (recentRoundEndTimes.nonEmpty) Some(recentRoundEndTimes) else None,
        penaltyUntil = penaltyUntil.filter(_.nonEmpty)
      )
    }

    implicit val _artifact: Lens[GlobalConsensusOutcome, Signed[GlobalSnapshotArtifact]] =
      GenLens[GlobalConsensusOutcome](_.finished.signedMajorityArtifact)
    implicit val _context: Lens[GlobalConsensusOutcome, GlobalSnapshotContext] =
      GenLens[GlobalConsensusOutcome](_.finished.context)
    implicit val _facilitators: Lens[GlobalConsensusOutcome, Facilitators] =
      GenLens[GlobalConsensusOutcome](_.facilitators)
    implicit val _key: Lens[GlobalConsensusOutcome, GlobalSnapshotKey] =
      GenLens[GlobalConsensusOutcome](_.key)
    implicit val _trigger: Lens[GlobalConsensusOutcome, ConsensusTrigger] =
      GenLens[GlobalConsensusOutcome](_.finished.majorityTrigger)
  }
}
