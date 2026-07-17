package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

object ActiveFacilitatorAdmission {
  // Participation-ratio math is the shared, integer-only PeerQualityClassifier so the dag-l0 operator committee
  // view cannot drift from this admission filter (feedback_share_logic_no_drift). Imported so the call sites
  // below read unchanged.
  import PeerQualityClassifier.{meetsParticipationRatio, minParticipationRatioScaled, participationRatioScaled}

  /** Whether deterministic active-facilitator expansion is admitted on the round at `ordinalValue`.
    *
    * Expansion is throttled to one attempt every `expansionIntervalRounds` ordinals so the active set (and therefore the quorum
    * denominator) grows gradually rather than every round. This is the single source of truth for that cadence: the StateCreators gate the
    * actual admission on it (`maxExpansionThisRound = if (expansionAllowedThisRound) maxExpansionPerRound else 0`), and the StallDetector
    * gates expansion-candidate `AdmissionVote` emission on it so votes are only spread on rounds where they can be acted upon -- keeping
    * the two in lockstep (feedback_share_logic_no_drift). The interval is floored to 1 so a non-positive config value degrades to "every
    * round".
    */
  def expansionAllowedAtOrdinal(ordinalValue: Long, expansionIntervalRounds: Int): Boolean =
    ordinalValue % math.max(1, expansionIntervalRounds).toLong == 0L

  /** The active-set size the admission machinery grows toward. Single source of truth for the admission deficit gate: the advancers'
    * pre-proposal certificate wait (`maybeWaitForAdmissionCertificates`) waits only while `roundStartFacilitators.size` is below this, and
    * the StallDetector emits expansion-candidate `AdmissionVote`s only under the same condition -- so votes are spread exactly when the
    * consumer can use them and stop when the active set is at capacity (at-capacity voting produced an admit-then-drop churn loop that made
    * AdmissionVotes ~77% of all gossip on IntegrationNet, v4.1.0). Falls back to `coreCommitteeSize`, then to the caller-supplied current
    * core size, mirroring the historical inline resolution in both advancers. NOTE: the configured target must exceed the Core floor (see
    * the scaled per-environment values in dag-l0.conf) or the gate closes before the Core can reach its floor and the committee wedges
    * below quorum feasibility.
    */
  def activeAdmissionTarget(
    activeFacilitatorTarget: Option[Int],
    coreCommitteeSize: Option[Int],
    currentCoreSize: Int
  ): Int =
    activeFacilitatorTarget.getOrElse(coreCommitteeSize.getOrElse(currentCoreSize))

  sealed abstract class ExclusionReason(val label: String)
  object ExclusionReason {
    case object NotRecentSigner extends ExclusionReason("not_recent_signer")
    case object QualityBelowThreshold extends ExclusionReason("quality_below_threshold")
    case object ScoreBelowPromoteThreshold extends ExclusionReason("score_below_promote_threshold")
    case object ScoreBelowRetainThreshold extends ExclusionReason("score_below_retain_threshold")
    case object ScoreBelowDemoteThreshold extends ExclusionReason("score_below_demote_threshold")
    case object MissedLatestRound extends ExclusionReason("missed_latest_round")
    case object BeyondTarget extends ExclusionReason("beyond_target")
    case object CertifiedTimeoutMissing extends ExclusionReason("certified_timeout_missing")
  }

  final case class Exclusion(peerId: PeerId, reason: ExclusionReason)

  final case class Result(
    active: List[PeerId],
    exclusions: List[Exclusion],
    recentSignerPoolSize: Int,
    candidateSize: Int,
    targetSize: Int,
    promotedCandidateSize: Int,
    scoreExcludedSize: Int,
    qualityExcludedSize: Int,
    demotedRecentSignerSize: Int,
    belowRetainRecentSignerSize: Int,
    expansionAdmittedSize: Int,
    reserveAdmitted: List[PeerId],
    reserveAdmittedSize: Int,
    probationAdmitted: List[PeerId],
    probationAdmittedSize: Int,
    stickyProbationCandidateSize: Int,
    freshProbationCandidateSize: Int,
    freshProbationStarved: Boolean,
    recentSignerMinCount: Int,
    recentSignerMaxCount: Int,
    recentWindowSize: Int,
    recentFilterApplied: Boolean
  )

  def fromRecentSigners(
    selected: List[PeerId],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    peerQuality: Map[PeerId, (Int, Int)],
    activeScores: Map[PeerId, Int] = Map.empty,
    minActiveSize: Int,
    targetActiveSize: Int,
    maxActiveSize: Int,
    minParticipationObservations: Int,
    minParticipationRatio: Double,
    promoteThreshold: Int = 100,
    retainThreshold: Int = 70,
    demoteThreshold: Int = 40,
    maxExpansionPerRound: Int = Int.MaxValue,
    // Bounded probation re-entry lane: minimum number of seats reserved for sticky below-retain
    // signers and fresh below-promote candidates EVEN WHEN the per-round expansion budget is
    // exhausted. Default 0 is fully inert.
    minProbationReentrySlots: Int = 0,
    // Lookback depth (in ordinals) of the recent-signer pool: how far back a peer may have last
    // signed and still count as a sticky, score-gated "recent signer" seat rather than churning
    // through the volatile expansion/reserve fill each round. Decoupled from the demotion hysteresis
    // `TierTransitions.DemotionConsecutiveMisses` (which stays at 3 and independently keeps non-recent
    // signers OUT of quorum-bearing Core), so widening this only changes active-set eligibility.
    // Floored internally to DemotionConsecutiveMisses so a low value cannot disable
    // `recentWindowDeepEnough`. Default preserves the pre-change 3-ordinal lookback.
    recentSignerWindow: Int = TierTransitions.DemotionConsecutiveMisses,
    // Signed round-start set from the latest controller-evidence entry. A peer that was asked to
    // sign but missed must not be immediately reclassified as a fresh probation candidate.
    latestRoundStartFacilitators: Set[PeerId] = Set.empty
  ): Result = {
    val minRatioScaled = minParticipationRatioScaled(minParticipationRatio)

    def qualityRank(pid: PeerId): (Int, Long, Int, String) =
      peerQuality.get(pid) match {
        case Some((completed, participated)) if participated > 0 =>
          val ratioScaled = participationRatioScaled(completed, participated)
          val qualityClass =
            if (participated >= minParticipationObservations && !meetsParticipationRatio(completed, participated, minRatioScaled)) 2
            else if (participated >= minParticipationObservations) 0
            else 1

          (qualityClass, -ratioScaled, -completed, pid.value.value)
        case _ =>
          (1, 0, 0, pid.value.value)
      }

    def isQualityExcluded(pid: PeerId): Boolean =
      peerQuality.get(pid).exists {
        case (completed, participated) if participated >= minParticipationObservations && participated > 0 =>
          !meetsParticipationRatio(completed, participated, minRatioScaled)
        case _ => false
      }

    // Floor the lookback to the demotion-hysteresis depth so a misconfigured low value cannot make
    // `recentWindowDeepEnough` false and silently disable the recent-signer path (Codex review #2).
    val effectiveRecentSignerWindow = math.max(TierTransitions.DemotionConsecutiveMisses, recentSignerWindow)
    val recentSets = recentSigners.values.toList.takeRight(effectiveRecentSignerWindow)
    val latestSignerSet = recentSets.lastOption.getOrElse(SortedSet.empty[PeerId])
    def recentSignerCount(pid: PeerId): Int = recentSets.count(_.contains(pid))
    val scoreHistoryAvailable = activeScores.nonEmpty
    def bootstrapScore(pid: PeerId): Int =
      peerQuality.get(pid) match {
        case Some((completed, participated))
            if participated >= minParticipationObservations && participated > 0 &&
              meetsParticipationRatio(completed, participated, minRatioScaled) =>
          promoteThreshold
        case _ if recentSignerCount(pid) > 0 => retainThreshold
        case _                               => 0
      }
    def scoreOf(pid: PeerId): Int = activeScores.getOrElse(pid, bootstrapScore(pid))
    def recentSignerRank(pid: PeerId): (Int, Int, Int, Long, Int, String) = {
      val (qualityClass, ratioRank, completedRank, peerIdRank) = qualityRank(pid)
      (-recentSignerCount(pid), -scoreOf(pid), qualityClass, ratioRank, completedRank, peerIdRank)
    }

    val recentWindowDeepEnough = recentSets.sizeIs >= TierTransitions.DemotionConsecutiveMisses
    val (demotedRecentSigners, retainedOrSoftExcludedRecentSigners) =
      if (recentWindowDeepEnough)
        selected.filter(pid => recentSets.exists(_.contains(pid))).partition(pid => scoreHistoryAvailable && scoreOf(pid) < demoteThreshold)
      else
        (List.empty[PeerId], selected)
    val (belowRetainRecentSigners, retainedRecentSignerCandidates) =
      if (recentWindowDeepEnough)
        retainedOrSoftExcludedRecentSigners.partition(pid => scoreHistoryAvailable && scoreOf(pid) < retainThreshold)
      else
        (List.empty[PeerId], retainedOrSoftExcludedRecentSigners)
    val uncappedRecentSignerPool = retainedRecentSignerCandidates.sortBy(recentSignerRank)
    val configuredMax = math.max(minActiveSize, maxActiveSize)
    val target =
      math.min(
        selected.size,
        math.min(configuredMax, List(minActiveSize, targetActiveSize, uncappedRecentSignerPool.size).max)
      )
    val recentSignerPool = uncappedRecentSignerPool.take(target)
    val recentSignerSet = recentSignerPool.toSet
    val recentScoreExcludedSet = (demotedRecentSigners ++ belowRetainRecentSigners).toSet
    val admittedRecentSignerCounts = recentSignerPool.map(recentSignerCount)
    val recentSignerMinCount = admittedRecentSignerCounts.minOption.getOrElse(0)
    val recentSignerMaxCount = admittedRecentSignerCounts.maxOption.getOrElse(0)
    val excludedRecentOverflow =
      if (uncappedRecentSignerPool.size > recentSignerPool.size)
        uncappedRecentSignerPool.drop(recentSignerPool.size).map(Exclusion(_, ExclusionReason.BeyondTarget))
      else
        List.empty
    val useRecentSignerPool = recentWindowDeepEnough && uncappedRecentSignerPool.size >= minActiveSize

    val expansionBase =
      if (useRecentSignerPool)
        selected.filterNot(pid => recentSignerSet.contains(pid) || recentScoreExcludedSet.contains(pid))
      else
        List.empty
    val (qualityExcluded, qualityExpansionCandidates) = expansionBase.partition(isQualityExcluded)
    val (scoreExcluded, promotedExpansionCandidates) = qualityExpansionCandidates.partition(pid => scoreOf(pid) < promoteThreshold)
    def expansionRank(pid: PeerId): (Int, Int, Long, Int, String) = {
      val (qualityClass, ratioRank, completedRank, peerIdRank) = qualityRank(pid)
      (-scoreOf(pid), qualityClass, ratioRank, completedRank, peerIdRank)
    }
    def probationRank(pid: PeerId): (Int, Long, Int, String) = {
      val (qualityClass, ratioRank, completedRank, peerIdRank) = qualityRank(pid)
      (qualityClass, ratioRank, completedRank, peerIdRank)
    }
    val sortedPromotedExpansionCandidates = promotedExpansionCandidates.sortBy(expansionRank)
    val expansionSlots = math.max(0, target - recentSignerPool.size)
    val expansionLimit = math.min(expansionSlots, maxExpansionPerRound)
    val promotedAdmitted = sortedPromotedExpansionCandidates.take(expansionLimit)
    // Bounded sticky probation lane. A below-retain recent signer that completed the latest round
    // keeps competing for a probation seat, so it can accumulate the consecutive signed evidence
    // needed to reach the retain band. Missing the latest round ends that lease immediately.
    // Existing climbers rank ahead of fresh score-excluded candidates; both remain capped by the
    // configured probation headroom and are returned in `probationAdmitted`, which keeps them out
    // of Core in CommitteeBuilder. All inputs are signed evidence and the ranking ends in PeerId.
    val stickyProbationCandidates =
      (demotedRecentSigners ++ belowRetainRecentSigners)
        .filter(latestSignerSet.contains)
        .distinct
        .sortBy(probationRank)
    val latestRoundMisses = latestRoundStartFacilitators -- latestSignerSet
    val (missedLatestRoundCandidates, eligibleFreshProbationCandidates) =
      scoreExcluded.partition(latestRoundMisses.contains)
    val freshProbationCandidates = eligibleFreshProbationCandidates.sortBy(probationRank)
    val probationCandidates = stickyProbationCandidates ++ freshProbationCandidates
    val expansionProbationSlots = math.max(0, expansionLimit - promotedAdmitted.size)
    val reentryHeadroom = math.max(0, configuredMax - recentSignerPool.size - promotedAdmitted.size)
    val probationSlots = math.min(reentryHeadroom, math.max(expansionProbationSlots, minProbationReentrySlots))
    val probationAdmitted = probationCandidates.take(probationSlots)
    val freshProbationStarved =
      probationSlots > 0 &&
        stickyProbationCandidates.size >= probationSlots &&
        freshProbationCandidates.nonEmpty
    val expansionAdmitted = promotedAdmitted ++ probationAdmitted
    val expansionAdmittedSet = expansionAdmitted.toSet
    val reserveSlots = math.max(0, target - recentSignerPool.size - expansionAdmitted.size)
    val reserveAdmitted = sortedPromotedExpansionCandidates.filterNot(expansionAdmittedSet.contains).take(reserveSlots)
    val reserveAdmittedSet = reserveAdmitted.toSet
    val beyondTarget =
      sortedPromotedExpansionCandidates.filterNot(pid => expansionAdmittedSet.contains(pid) || reserveAdmittedSet.contains(pid))
    val demotedRejected = demotedRecentSigners.filterNot(expansionAdmittedSet.contains)
    val belowRetainRejected = belowRetainRecentSigners.filterNot(expansionAdmittedSet.contains)
    val probationRejected = freshProbationCandidates.filterNot(expansionAdmittedSet.contains)

    val active =
      if (useRecentSignerPool)
        recentSignerPool ++ expansionAdmitted ++ reserveAdmitted
      else
        selected.take(target)
    val exclusions =
      if (useRecentSignerPool)
        demotedRejected.map(Exclusion(_, ExclusionReason.ScoreBelowDemoteThreshold)) ++
          belowRetainRejected.map(Exclusion(_, ExclusionReason.ScoreBelowRetainThreshold)) ++
          excludedRecentOverflow ++
          qualityExcluded.map(Exclusion(_, ExclusionReason.QualityBelowThreshold)) ++
          missedLatestRoundCandidates.map(Exclusion(_, ExclusionReason.MissedLatestRound)) ++
          probationRejected.map(Exclusion(_, ExclusionReason.ScoreBelowPromoteThreshold)) ++
          beyondTarget.map(Exclusion(_, ExclusionReason.BeyondTarget))
      else
        List.empty

    Result(
      active = active,
      exclusions = exclusions,
      recentSignerPoolSize = uncappedRecentSignerPool.size,
      candidateSize = recentSignerPool.size + promotedExpansionCandidates.size + probationCandidates.size,
      targetSize = target,
      promotedCandidateSize = promotedExpansionCandidates.size,
      scoreExcludedSize = scoreExcluded.size,
      qualityExcludedSize = qualityExcluded.size,
      demotedRecentSignerSize = demotedRecentSigners.size,
      belowRetainRecentSignerSize = belowRetainRecentSigners.size,
      expansionAdmittedSize = expansionAdmitted.size,
      reserveAdmitted = reserveAdmitted,
      reserveAdmittedSize = reserveAdmitted.size,
      probationAdmitted = probationAdmitted,
      probationAdmittedSize = probationAdmitted.size,
      stickyProbationCandidateSize = stickyProbationCandidates.size,
      freshProbationCandidateSize = freshProbationCandidates.size,
      freshProbationStarved = freshProbationStarved,
      recentSignerMinCount = recentSignerMinCount,
      recentSignerMaxCount = recentSignerMaxCount,
      recentWindowSize = recentSets.size,
      recentFilterApplied = useRecentSignerPool
    )
  }

  def fromCertifiedTimeout(
    selected: List[PeerId],
    recentSigners: SortedMap[SnapshotOrdinal, SortedSet[PeerId]],
    timeoutVoters: Set[PeerId],
    minActiveSize: Int
  ): Result = {
    val recentSets = recentSigners.values.toList.takeRight(TierTransitions.DemotionConsecutiveMisses)
    val recentSignerPool = selected.filter(pid => recentSets.exists(_.contains(pid)))
    val timeoutRetained = selected.filter(timeoutVoters.contains)
    val deterministicFill =
      selected
        .filter(pid => recentSignerPool.contains(pid) && !timeoutRetained.contains(pid))
        .take((minActiveSize - timeoutRetained.size).max(0))
    val retained = timeoutRetained ++ deterministicFill
    val useCertifiedShrink = retained.size >= minActiveSize && retained.size < selected.size
    val active = if (useCertifiedShrink) retained else selected
    val exclusions =
      if (useCertifiedShrink)
        selected.filterNot(active.toSet).map(Exclusion(_, ExclusionReason.CertifiedTimeoutMissing))
      else
        List.empty

    Result(
      active = active,
      exclusions = exclusions,
      recentSignerPoolSize = recentSignerPool.size,
      candidateSize = retained.size,
      targetSize = minActiveSize,
      promotedCandidateSize = 0,
      scoreExcludedSize = 0,
      qualityExcludedSize = 0,
      demotedRecentSignerSize = 0,
      belowRetainRecentSignerSize = 0,
      expansionAdmittedSize = 0,
      reserveAdmitted = List.empty,
      reserveAdmittedSize = 0,
      probationAdmitted = List.empty,
      probationAdmittedSize = 0,
      stickyProbationCandidateSize = 0,
      freshProbationCandidateSize = 0,
      freshProbationStarved = false,
      recentSignerMinCount = recentSignerPool.map(pid => recentSets.count(_.contains(pid))).minOption.getOrElse(0),
      recentSignerMaxCount = recentSignerPool.map(pid => recentSets.count(_.contains(pid))).maxOption.getOrElse(0),
      recentWindowSize = recentSets.size,
      recentFilterApplied = useCertifiedShrink
    )
  }
}
