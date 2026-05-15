package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Show
import cats.syntax.show._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ConsensusOperationalState, PerPeerOperationalRecord, SnapshotOrdinal}
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
            observedResponders,
            observedSelfHealth
          ) =>
        s"CollectingProposals{majorityTrigger=${majorityTrigger.show}, proposalArtifactInfo=${proposalArtifactInfo.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}, observedRespondersCount=${observedResponders.size}, observedSelfHealthCount=${observedSelfHealth.size}}"
      case CollectingSignatures(majorityArtifactInfo, majorityTrigger, candidates, facilitatorsHash, lastSnapshotHash) =>
        s"CollectingSignatures{majorityArtifactInfo=${majorityArtifactInfo.show}, ${majorityTrigger.show}, candidates=${candidates.show}, facilitatorsHash=${facilitatorsHash.show}, lastSnapshotHash=${lastSnapshotHash.show}}"
      case Finished(_, _, majorityTrigger, candidates, facilitatorsHash, snapshotHash) =>
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
    // v7 (codex turn 2 fix #2): leader's observedResponders frozen at proposal-build time
    // so leader re-spread (advancer:889 → spreadProposal:2098) reads from this immutable
    // status field instead of recomputing from current resources. Without this, every
    // re-spread could produce a different observedResponders set, breaking determinism.
    observedResponders: List[PeerId],
    // v15: same byte-identical-re-spread rationale as observedResponders. The aggregated
    // selfHealth map is frozen here at proposal-build time so any retransmit reproduces the
    // original Proposal payload exactly. SortedMap so circe encodes in deterministic key order.
    observedSelfHealth: SortedMap[PeerId, SelfHealthHint]
  ) extends GlobalConsensusStep

  final case class CollectingSignatures(
    majorityArtifactInfo: ArtifactInfo[GlobalSnapshotArtifact, GlobalSnapshotContext],
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash
  ) extends GlobalConsensusStep

  @derive(encoder, decoder, eqv)
  final case class Finished(
    signedMajorityArtifact: Signed[GlobalSnapshotArtifact],
    context: GlobalSnapshotContext,
    majorityTrigger: ConsensusTrigger,
    candidates: Candidates,
    facilitatorsHash: Hash,
    snapshotHash: Hash
  ) extends GlobalConsensusStep

  /** Outcome of a completed consensus round, carried forward as `lastOutcome` into the next round.
    *
    * `removalPenalties` uses `SortedMap` to ensure deterministic iteration when computing penalty decrements and filtering penalized peers
    * in the next round.
    *
    * `peerQuality` tracks consensus-agreed quality scores: `(roundsCompleted, roundsParticipated)` per peer. Because all nodes in a round
    * agree on the same facilitator list, removals, and withdrawals, these counters are deterministic across the network — enabling
    * quality-weighted leader selection without local score divergence.
    */
  @derive(encoder, decoder, eqv)
  final case class GlobalConsensusOutcome(
    key: GlobalSnapshotKey,
    facilitators: Facilitators,
    removedFacilitators: RemovedFacilitators,
    withdrawnFacilitators: WithdrawnFacilitators,
    eligibleFacilitators: EligibleFacilitators,
    finished: Finished,
    removalPenalties: SortedMap[PeerId, Int] = SortedMap.empty,
    deferralCountdown: SortedMap[PeerId, Int] = SortedMap.empty,
    peerQuality: SortedMap[PeerId, (Int, Int)] = SortedMap.empty,
    // Lifetime count of times this peer was evicted/removed. Used to scale the
    // `removalPenaltyRounds` exponentially so repeat offenders get progressively
    // longer bans. Persisted in the signed outcome → consensus-agreed → all nodes
    // compute the same penalty. Defaults to empty so old outcomes roll over cleanly.
    cumulativeMissCounts: SortedMap[PeerId, Long] = SortedMap.empty,
    // Sliding window of (ordinal -> proofs.size) for the last ~10 ordinals. Used to
    // classify whether the chain has completed initial bootstrap (any entry >=
    // bootstrapCompleteProofsThreshold => post-bootstrap). While in bootstrap,
    // penalty accrual is suppressed so slow peers aren't ejected during the
    // solo->multi transition. Chain-derived: seeded from `lastNGlobalSnapshotStorage`
    // on rollback init, rolls forward one entry per outcome. Non-monotonic by
    // design — if the cluster degrades to solo for > lookback ordinals, warmup
    // re-engages, which is appropriate for re-stabilizing after mass peer loss.
    recentProofSizes: SortedMap[SnapshotOrdinal, Int] = SortedMap.empty,
    // B2 re-admission gate: peers whose `removalPenalty` expired enter this map
    // at `readmissionProbationRounds` and count down one per finished round. While
    // the entry exists, the peer is excluded from both `fullBase` and
    // `potentiallyCompeting` in state creation (non-bypassable, even on the
    // withoutPenaltiesOnly path). Removal from this map happens when the advancer
    // observes the peer in a quorum-certified AdmissionCertificate embedded in a
    // proposal (re-admitted via consensus-witnessed current-tip participation).
    // Consensus-agreed → deterministic across all peers.
    readmissionCountdown: SortedMap[PeerId, Int] = SortedMap.empty,
    // v15 (2026-05-15) self-health throttle: each peer's last-known `SelfHealthHint`
    // copied from the accepted Proposal's `observedSelfHealth`. Read by the next round's
    // `selectLeaderWeighted` to demote Degraded peers to tier 1 and Critical peers to
    // tier 2 (strong demote, not hard exclude -- keeps liveness if all peers report Critical).
    // Peers absent from the map default to `Healthy` at read time. Not persisted into the
    // snapshot operational state (see docs/consensus/self-health-throttle.md, open decision
    // 4): a freshly-restarted cluster picks leaders without hints until the first round of
    // facilities arrives.
    peerSelfHealth: SortedMap[PeerId, SelfHealthHint] = SortedMap.empty
  ) {
    def eligibleOrFacilitators: List[PeerId] =
      if (eligibleFacilitators.value.nonEmpty) eligibleFacilitators.value
      else facilitators.value

    // v20: package the consensus-derived peer-behavior counters for persistence
    // on the next round's incremental snapshot. All inputs are consensus-agreed,
    // so every facilitator produces a byte-identical result.
    //
    // v21 layout: peer-keyed dimensions collapsed into a single map keyed by
    // PeerId so each id appears once. The union of keys across the five
    // dimensions becomes the per-peer map's key set; absent peers contribute
    // `PerPeerOperationalRecord.empty` semantics on the consumer side.
    def toOperationalState: ConsensusOperationalState = {
      val keys: Set[PeerId] =
        (peerQuality.keysIterator ++
          removalPenalties.keysIterator ++
          cumulativeMissCounts.keysIterator ++
          readmissionCountdown.keysIterator ++
          deferralCountdown.keysIterator).toSet
      val perPeer: SortedMap[PeerId, PerPeerOperationalRecord] =
        SortedMap.from(
          keys.iterator.map { pid =>
            pid -> PerPeerOperationalRecord(
              quality = peerQuality.getOrElse(pid, (0, 0)),
              removalPenalty = removalPenalties.getOrElse(pid, 0),
              cumulativeMissCount = cumulativeMissCounts.getOrElse(pid, 0L),
              readmissionCountdown = readmissionCountdown.getOrElse(pid, 0),
              deferralCountdown = deferralCountdown.getOrElse(pid, 0)
            )
          }
        )
      ConsensusOperationalState(perPeer = perPeer, recentProofSizes = recentProofSizes)
    }
  }

  @derive(encoder, decoder, eqv, show)
  sealed trait GlobalConsensusKind

  object GlobalConsensusKind {
    case object Facility extends GlobalConsensusKind

    case object Proposal extends GlobalConsensusKind

    case object Signature extends GlobalConsensusKind
  }

  object GlobalConsensusOutcome {
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
