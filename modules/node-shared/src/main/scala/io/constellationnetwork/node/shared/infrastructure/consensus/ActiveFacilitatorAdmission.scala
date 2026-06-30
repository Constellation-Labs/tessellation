package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

object ActiveFacilitatorAdmission {
  // Participation-ratio math is the shared, integer-only PeerQualityClassifier so the dag-l0 operator committee
  // view cannot drift from this admission filter (feedback_share_logic_no_drift). Imported so the call sites
  // below read unchanged.
  import PeerQualityClassifier.{meetsParticipationRatio, minParticipationRatioScaled, participationRatioScaled}

  sealed abstract class ExclusionReason(val label: String)
  object ExclusionReason {
    case object NotRecentSigner extends ExclusionReason("not_recent_signer")
    case object QualityBelowThreshold extends ExclusionReason("quality_below_threshold")
    case object ScoreBelowPromoteThreshold extends ExclusionReason("score_below_promote_threshold")
    case object ScoreBelowRetainThreshold extends ExclusionReason("score_below_retain_threshold")
    case object ScoreBelowDemoteThreshold extends ExclusionReason("score_below_demote_threshold")
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
    // Bounded probation re-entry lane: minimum number of probation slots reserved for
    // below-promote-threshold "rehabilitating" peers (`scoreExcluded`) EVEN WHEN the per-round
    // expansion budget is exhausted. Default 0 is fully inert (preserves pre-fix behavior). See
    // the inline note at the `probationSlots` computation for the catch-22 this breaks.
    minProbationReentrySlots: Int = 0,
    // Lookback depth (in ordinals) of the recent-signer pool: how far back a peer may have last
    // signed and still count as a sticky, score-gated "recent signer" seat rather than churning
    // through the volatile expansion/reserve fill each round. Decoupled from the demotion hysteresis
    // `TierTransitions.DemotionConsecutiveMisses` (which stays at 3 and independently keeps non-recent
    // signers OUT of quorum-bearing Core), so widening this only changes active-set eligibility.
    // Floored internally to DemotionConsecutiveMisses so a low value cannot disable
    // `recentWindowDeepEnough`. Default preserves the pre-change 3-ordinal lookback.
    recentSignerWindow: Int = TierTransitions.DemotionConsecutiveMisses
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
    // Bounded probation re-entry lane. `expansionProbationSlots` is the legacy budget: whatever
    // is left of this round's expansion limit after promoted expansion. `minProbationReentrySlots`
    // guarantees up to K probation slots EVEN WHEN that budget is 0, decoupled from
    // `maxExpansionPerRound`, so a mass return after a cluster-wide outage drains in a bounded
    // number of rounds instead of never (the catch-22: rehabilitating peers need to sign to
    // rebuild their score, but the only re-entry path was throttled to ~1/round shared with
    // promoted expansion, so the active set ratcheted down to the always-on core and never grew
    // back). `reentryHeadroom` caps the lane by `configuredMax` (= max(minActiveSize, maxActiveSize),
    // the floor-wins-over-ceiling active-set bound) so it can never overflow the signing set beyond
    // what the size config already permits. Probation peers are non-quorum-bearing (they flow to
    // nonCorePeers in CommitteeBuilder) so widening the lane cannot affect quorum feasibility.
    // Deterministic: K is a constant and `probationRank` ends in a PeerId tiebreak, so committee
    // derivation stays fork-safe. Inert when `minProbationReentrySlots == 0`.
    val expansionProbationSlots = math.max(0, expansionLimit - promotedAdmitted.size)
    val reentryHeadroom = math.max(0, configuredMax - recentSignerPool.size - promotedAdmitted.size)
    val probationSlots = math.min(reentryHeadroom, math.max(expansionProbationSlots, minProbationReentrySlots))
    val probationAdmitted = scoreExcluded.sortBy(probationRank).take(probationSlots)
    val expansionAdmitted = promotedAdmitted ++ probationAdmitted
    val expansionAdmittedSet = expansionAdmitted.toSet
    val reserveSlots = math.max(0, target - recentSignerPool.size - expansionAdmitted.size)
    val reserveAdmitted = sortedPromotedExpansionCandidates.filterNot(expansionAdmittedSet.contains).take(reserveSlots)
    val reserveAdmittedSet = reserveAdmitted.toSet
    val beyondTarget =
      sortedPromotedExpansionCandidates.filterNot(pid => expansionAdmittedSet.contains(pid) || reserveAdmittedSet.contains(pid))
    val probationRejected = scoreExcluded.filterNot(expansionAdmittedSet.contains)

    val active =
      if (useRecentSignerPool)
        recentSignerPool ++ expansionAdmitted ++ reserveAdmitted
      else
        selected.take(target)
    val exclusions =
      if (useRecentSignerPool)
        demotedRecentSigners.map(Exclusion(_, ExclusionReason.ScoreBelowDemoteThreshold)) ++
          belowRetainRecentSigners.map(Exclusion(_, ExclusionReason.ScoreBelowRetainThreshold)) ++
          excludedRecentOverflow ++
          qualityExcluded.map(Exclusion(_, ExclusionReason.QualityBelowThreshold)) ++
          probationRejected.map(Exclusion(_, ExclusionReason.ScoreBelowPromoteThreshold)) ++
          beyondTarget.map(Exclusion(_, ExclusionReason.BeyondTarget))
      else
        List.empty

    Result(
      active = active,
      exclusions = exclusions,
      recentSignerPoolSize = uncappedRecentSignerPool.size,
      candidateSize = recentSignerPool.size + promotedExpansionCandidates.size + scoreExcluded.size,
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
      recentSignerMinCount = recentSignerPool.map(pid => recentSets.count(_.contains(pid))).minOption.getOrElse(0),
      recentSignerMaxCount = recentSignerPool.map(pid => recentSets.count(_.contains(pid))).maxOption.getOrElse(0),
      recentWindowSize = recentSets.size,
      recentFilterApplied = useCertifiedShrink
    )
  }
}
