package io.constellationnetwork.currency.l0.snapshot

import cats.Show
import cats.syntax.show._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotArtifact
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import monocle.Lens
import monocle.macros.GenLens

object schema {

  @derive(eqv)
  sealed trait CurrencyConsensusStep

  object CurrencyConsensusStep {
    implicit val show: Show[CurrencyConsensusStep] = Show.show {
      case CollectingFacilities(maybeTrigger, facilitatorsHash, lastSnapshotHash) =>
        s"CollectingFacilities{maybeTrigger=${maybeTrigger.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}}"
      case CollectingProposals(
            majorityTrigger,
            proposalArtifactInfo,
            candidates,
            facilitatorsHash,
            lastSnapshotHash,
            observedResponders,
            observedSelfHealth
          ) =>
        s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, proposalArtifactInfo=${proposalArtifactInfo.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}, observedRespondersCount=${observedResponders.size}, observedSelfHealthCount=${observedSelfHealth.size}}"
      case CollectingSignatures(majorityArtifactInfo, majorityTrigger, candidates, facilitatorsHash, lastSnapshotHash) =>
        s"CollectingSignatures{majorityArtifactInfo=${majorityArtifactInfo.show}, ${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}}"
      case CollectingBinarySignatures(_, _, _, majorityTrigger, candidates, facilitatorsHash, lastSnapshotHash) =>
        s"CollectingBinarySignatures{majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}}"
      case Finished(_, binaryArtifactHash, _, majorityTrigger, candidates, facilitatorsHash, snapshotHash) =>
        s"Finished{binaryArtifactHash=${binaryArtifactHash}, majorityTrigger=${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, snapshotHash=${snapshotHash.show}}"
    }
  }

  final case class CollectingFacilities(
    maybeTrigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends CurrencyConsensusStep

  final case class CollectingProposals(
    majorityTrigger: ConsensusTrigger,
    proposalArtifactInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
    candidates: Candidates,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    // v7 (codex turn 2 fix #2): leader's observedResponders frozen at proposal-build time —
    // see dag-l0 mirror for full rationale. Re-spread reads from this immutable status field
    // instead of recomputing from current resources.
    observedResponders: List[PeerId],
    // v15: same byte-identical-re-spread rationale as observedResponders. The aggregated
    // selfHealth map is frozen here at proposal-build time so any retransmit reproduces the
    // original Proposal payload exactly.
    observedSelfHealth: SortedMap[PeerId, SelfHealthHint]
  ) extends CurrencyConsensusStep

  final case class CollectingSignatures(
    majorityArtifactInfo: ArtifactInfo[CurrencySnapshotArtifact, CurrencySnapshotContext],
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends CurrencyConsensusStep

  final case class CollectingBinarySignatures(
    signedMajorityArtifact: Signed[CurrencySnapshotArtifact],
    context: CurrencySnapshotContext,
    binary: StateChannelSnapshotBinary,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends CurrencyConsensusStep

  @derive(encoder, decoder, eqv)
  final case class Finished(
    signedMajorityArtifact: Signed[CurrencySnapshotArtifact],
    binaryArtifactHash: Hash,
    context: CurrencySnapshotContext,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    snapshotHash: Hash
  ) extends CurrencyConsensusStep

  @derive(encoder, decoder, eqv, show)
  sealed trait CurrencyConsensusKind
  object CurrencyConsensusKind {
    case object Facility extends CurrencyConsensusKind

    case object Proposal extends CurrencyConsensusKind

    case object Signature extends CurrencyConsensusKind

    case object BinarySignature extends CurrencyConsensusKind
  }

  @derive(encoder, decoder, eqv)
  final case class CurrencyConsensusOutcome(
    key: CurrencySnapshotKey,
    facilitators: Facilitators,
    removedFacilitators: RemovedFacilitators,
    withdrawnFacilitators: WithdrawnFacilitators,
    eligibleFacilitators: EligibleFacilitators,
    finished: Finished,
    removalPenalties: SortedMap[PeerId, Int] = SortedMap.empty,
    deferralCountdown: SortedMap[PeerId, Int] = SortedMap.empty,
    peerQuality: SortedMap[PeerId, (Int, Int)] = SortedMap.empty,
    // Lifetime count of times this peer was evicted. Scales removalPenaltyRounds
    // exponentially so repeat offenders get progressively longer bans.
    cumulativeMissCounts: SortedMap[PeerId, Long] = SortedMap.empty,
    // Sliding window of (ordinal -> proofs.size) for the last ~10 ordinals, used for
    // bootstrap warmup classification. See dag-l0 mirror for full rationale.
    recentProofSizes: SortedMap[SnapshotOrdinal, Int] = SortedMap.empty,
    // B2 re-admission gate: peers whose `removalPenalty` expired enter this map
    // at `readmissionProbationRounds` and count down one per finished round. See
    // dag-l0 mirror for full rationale.
    readmissionCountdown: SortedMap[PeerId, Int] = SortedMap.empty,
    // Self-health throttle mirror of dag-l0 schema; see dag-l0 for full
    // rationale.
    peerSelfHealth: SortedMap[PeerId, SelfHealthHint] = SortedMap.empty,
    // Cumulative view-change-caused counts mirror of dag-l0 schema; see
    // dag-l0 schema for the full pack/unpack + recompute-at-finalize contract.
    peerViewChanges: SortedMap[PeerId, Long] = SortedMap.empty,
    // v22: rolling K-round signer-set window, mirror of dag-l0 schema. Repopulated each
    // round and consumed by `TierTransitions.computeNextTiers` as the tier-demotion
    // hysteresis input (a Core peer absent from the most-recent
    // `TierTransitions.DemotionConsecutiveMisses` signer sets is demoted). See dag-l0
    // mirror for the full rationale on window semantics and Option-wrap at the snapshot
    // boundary.
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]] = SortedMap.empty,
    // v19/v22 multi-committee tier classification per peer. Mirror of dag-l0 schema. See
    // dag-l0 mirror for the full Tier 2 / Tier 1 / Tier 0 semantics and the
    // `TierTransitions.computeNextTiers` round-finalize derivation (Core peers absent from
    // the most-recent `DemotionConsecutiveMisses` signer sets demote to Tier 1).
    peerTiers: SortedMap[PeerId, Int] = SortedMap.empty,
    // v27 consensus peer controller score, mirror of dag-l0 schema.
    activeAdmissionScores: SortedMap[PeerId, Int] = SortedMap.empty,
    // v28 controller evidence: voters from the accepted proposal's TimeoutCertificate
    // for the just-finalized round. Mirror of dag-l0 schema.
    lastTimeoutCertificateVoters: SortedSet[PeerId] = SortedSet.empty,
    // v19 phase 2 view-from-time anchor mirror of dag-l0 schema. Sliding window of
    // (ordinal -> consensusEndTime) computed as the median of Facility.proposerClockMs
    // clamped against the parent. See dag-l0 mirror and docs/consensus/view-from-time-anchor.md.
    recentRoundEndTimes: SortedMap[SnapshotOrdinal, Long] = SortedMap.empty,
    // Controller evidence stage 1 (write-only, no consumer yet), mirror of dag-l0 schema:
    // bounded window of canonical per-round facts (`tighteningWindow` entries) feeding
    // `ControllerEvidenceDerivation`. See dag-l0 mirror for the full rationale.
    controllerEvidence: Option[SortedMap[SnapshotOrdinal, ControllerEvidenceEntry]] = None,
    // Controller evidence stage 3 (write-only, no consumer yet), mirror of dag-l0 schema:
    // cert-anchored absolute penalty horizon per peer. See dag-l0 mirror.
    penaltyUntil: Option[SortedMap[PeerId, SnapshotOrdinal]] = None
  ) {
    def eligibleOrFacilitators: List[PeerId] =
      if (eligibleFacilitators.value.nonEmpty) eligibleFacilitators.value
      else facilitators.value

    // Mirror of GlobalConsensusOutcome.toOperationalState. See dag-l0 schema.
    def toOperationalState: ConsensusOperationalState = {
      val keys: Set[PeerId] =
        peerQuality.keySet |
          removalPenalties.keySet |
          cumulativeMissCounts.keySet |
          readmissionCountdown.keySet |
          deferralCountdown.keySet |
          peerViewChanges.keySet |
          peerTiers.keySet |
          activeAdmissionScores.keySet
      val perPeer: SortedMap[PeerId, PerPeerOperationalRecord] =
        SortedMap.from(
          keys.iterator.map { pid =>
            pid -> PerPeerOperationalRecord(
              quality = peerQuality.getOrElse(pid, (0, 0)),
              removalPenalty = removalPenalties.getOrElse(pid, 0),
              cumulativeMissCount = cumulativeMissCounts.getOrElse(pid, 0L),
              readmissionCountdown = ReadmissionMaintenance.persistenceValue(readmissionCountdown, pid),
              deferralCountdown = deferralCountdown.getOrElse(pid, 0),
              // v16: Option-wrap so absent peers / pre-v16 readers see no key under
              // dropNullValues=true. Mirror of dag-l0 schema.
              viewChangesCaused = peerViewChanges.get(pid).filter(_ > 0L),
              // v19: only emit Some when there is an actual tier classification.
              tier = peerTiers.get(pid),
              activeAdmissionScore = activeAdmissionScores.get(pid).filter(_ > 0)
            )
          }
        )
      ConsensusOperationalState(
        perPeer = perPeer,
        recentProofSizes = recentProofSizes,
        recentSigners = if (recentSigners.nonEmpty) Some(recentSigners) else None,
        // v19 phase 2: mirror of dag-l0 schema -- None at the snapshot boundary keeps
        // pre-v19 encodings byte-stable; populated only once the median is computable.
        recentRoundEndTimes = if (recentRoundEndTimes.nonEmpty) Some(recentRoundEndTimes) else None,
        // Stage 4: persist the evidence window + cert-anchored penalties across the
        // restart boundary. Mirror of dag-l0 schema.
        controllerEvidence = controllerEvidence.filter(_.nonEmpty),
        penaltyUntil = penaltyUntil.filter(_.nonEmpty)
      )
    }

    // Stage 4: evidence-only signed-artifact peerHistory payload. Mirror of dag-l0 schema;
    // both delegate to the shared helper so the signed subsets cannot drift.
    def signedArtifactPeerHistory: ConsensusOperationalState =
      ControllerEvidenceDerivation.signedArtifactOperationalState(
        recentProofSizes = recentProofSizes,
        recentSigners = recentSigners,
        controllerEvidence = controllerEvidence,
        penaltyUntil = penaltyUntil
      )
  }

  object CurrencyConsensusOutcome {
    implicit val _artifact: Lens[CurrencyConsensusOutcome, Signed[CurrencySnapshotArtifact]] =
      GenLens[CurrencyConsensusOutcome](_.finished.signedMajorityArtifact)
    implicit val _context: Lens[CurrencyConsensusOutcome, CurrencySnapshotContext] =
      GenLens[CurrencyConsensusOutcome](_.finished.context)
    implicit val _facilitators: Lens[CurrencyConsensusOutcome, Facilitators] =
      GenLens[CurrencyConsensusOutcome](_.facilitators)
    implicit val _key: Lens[CurrencyConsensusOutcome, CurrencySnapshotKey] =
      GenLens[CurrencyConsensusOutcome](_.key)
    implicit val _trigger: Lens[CurrencyConsensusOutcome, ConsensusTrigger] =
      GenLens[CurrencyConsensusOutcome](_.finished.majorityTrigger)
  }
}
