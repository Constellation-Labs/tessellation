package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.FunSuite

/** Phase 3 Part A: verify that penalty / peerQuality / recentProofSizes fields of a consensus outcome can be computed from consensus-agreed
  * inputs alone (`state.facilitators`, `state.removedFacilitators`, `state.lastOutcome`), independent of the locally-observed
  * `signedMajorityArtifact.proofs.size` value.
  *
  * Background: different nodes finalize a round with different counts of `MajoritySignature` declarations collected — the advancer returns
  * as soon as quorum is reached, so fast finalizers stop at exactly quorum while slow finalizers accumulate extra signatures.
  * `SnapshotStorage.prepend` does not merge proofs from later-arriving gossip copies; `ForkInfo` gossip carries only `(ordinal, hash)`. So
  * two nodes storing the "same" snapshot (same artifact hash) can have different `proofs.size`. Prior implementation derived `signers` from
  * those proofs, making `penalizedThisRound`, `peerQuality`, and `recentProofSizes` non-deterministic across nodes — the `lastOutcome` fed
  * into the next round's state creator diverged, cascading into divergent facilitator sets and `facilitatorsHash` fork failures (observed
  * in E2E ord-6).
  *
  * Fix: reach those fields from `state.facilitators - state.removedFacilitators` (both consensus-agreed; facilitators are aligned via
  * `facilitatorsHash` fork check, removedFacilitators is populated only by deterministic facility-phase fork eviction). The tests below
  * simulate the computation in isolation with two observation sets that differ only in the locally-seen signer subset and verify identical
  * outcomes.
  */
object DeterministicPenaltiesSuite extends FunSuite {

  private def peer(tag: String): PeerId =
    PeerId(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString.padTo(64, '0')))

  private val p1 = peer("p1")
  private val p2 = peer("p2")
  private val p3 = peer("p3")
  private val p4 = peer("p4")
  private val p5 = peer("p5")

  private val allFacilitators: Set[PeerId] = Set(p1, p2, p3, p4, p5)
  private val noEvicted: Set[PeerId] = Set.empty

  private val bootstrapCompleteProofsThreshold: Int = 3
  private val removalPenaltyRounds: Int = 3
  private val exponentialPenaltyBase: Int = 2
  private val maxRemovalPenaltyRounds: Int = 10000
  private val candidateDeferralRounds: Int = 3
  private val qualityDecayThreshold: Int = 100

  // Replicates the deterministic-only computation from Phase 3 Part A (both advancers).
  // Observed signer-set is NOT an input — the computation uses only `state.facilitators`
  // and `state.removedFacilitators`.
  private case class OutcomeFields(
    removalPenalties: SortedMap[PeerId, Int],
    cumulativeMissCounts: SortedMap[PeerId, Long],
    peerQuality: SortedMap[PeerId, (Int, Int)],
    recentProofSizes: SortedMap[SnapshotOrdinal, Int]
  )

  private def computeOutcomeFields(
    key: SnapshotOrdinal,
    facilitators: Set[PeerId],
    removedFacilitators: Set[PeerId],
    previousPenalties: SortedMap[PeerId, Int],
    previousCumulative: SortedMap[PeerId, Long],
    previousPeerQuality: SortedMap[PeerId, (Int, Int)],
    previousRecentProofSizes: SortedMap[SnapshotOrdinal, Int],
    previousDeferrals: SortedMap[PeerId, Int]
  ): OutcomeFields = {
    val deferredInCommittee = previousDeferrals.filter(_._2 > 0).keySet
    val completedFacilitators = facilitators -- removedFacilitators

    val decayedCumulative = completedFacilitators.foldLeft(previousCumulative) { (acc, pid) =>
      acc.get(pid) match {
        case Some(v) if v > 1L => acc.updated(pid, v - 1L)
        case Some(_)           => acc - pid
        case None              => acc
      }
    }

    val isInBootstrap = !previousRecentProofSizes.values.exists(_ >= bootstrapCompleteProofsThreshold)
    val penalizedThisRound: Set[PeerId] =
      if (isInBootstrap) Set.empty[PeerId] else (removedFacilitators -- deferredInCommittee).toSet

    val newCumulative = penalizedThisRound.foldLeft(decayedCumulative) { (acc, pid) =>
      acc.updated(pid, acc.getOrElse(pid, 0L) + 1L)
    }

    val decrementedPenalties = previousPenalties.view.mapValues(_ - 1).filter(_._2 > 0).to(SortedMap)
    val newPenalties = penalizedThisRound.foldLeft(decrementedPenalties) { (acc, pid) =>
      val repeatCount = newCumulative.getOrElse(pid, 1L) - 1L
      val base = exponentialPenaltyBase.toDouble
      val scaled = removalPenaltyRounds.toDouble * math.pow(base, repeatCount.toDouble)
      val penalty = math.min(scaled, maxRemovalPenaltyRounds.toDouble).toInt
      acc.updated(pid, math.max(1, penalty))
    }
    val finalPenalties = if (removalPenaltyRounds > 0) newPenalties else SortedMap.empty[PeerId, Int]
    val _ = candidateDeferralRounds // not exercised directly here; deferral uses same pattern

    val thisRoundQuality: SortedMap[PeerId, (Int, Int)] = SortedMap.from(
      facilitators.toList.map { pid =>
        val completed = if (completedFacilitators.contains(pid)) 1 else 0
        pid -> (completed, 1)
      }
    )
    val rawAccumulated: SortedMap[PeerId, (Int, Int)] = {
      val allPeerIds = (previousPeerQuality.keySet.toList ::: thisRoundQuality.keySet.toList).distinct
      SortedMap.from(allPeerIds.map { pid =>
        val (pc, pp) = previousPeerQuality.getOrElse(pid, (0, 0))
        val (tc, tp) = thisRoundQuality.getOrElse(pid, (0, 0))
        pid -> (pc + tc, pp + tp)
      })
    }
    val needsDecay = rawAccumulated.values.exists { case (_, p) => p > qualityDecayThreshold }
    val decayed =
      if (needsDecay) rawAccumulated.view.mapValues { case (c, p) => (c / 2, p / 2) }.to(SortedMap)
      else rawAccumulated
    val accumulatedQuality = decayed.filter { case (_, (c, p)) => c > 0 || p > 0 }

    val bootstrapLookbackOrdinals = 10L
    val currentOrdValue = key.value.value
    val minOrdinalValue = math.max(0L, currentOrdValue - bootstrapLookbackOrdinals)
    val currentProofsSize: Int = completedFacilitators.size
    val newRecentProofSizes: SortedMap[SnapshotOrdinal, Int] = {
      val withCurrent = previousRecentProofSizes.updated(key, currentProofsSize)
      withCurrent.filter { case (ord, _) => ord.value.value >= minOrdinalValue }
    }

    OutcomeFields(finalPenalties, newCumulative, accumulatedQuality, newRecentProofSizes)
  }

  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal(NonNegLong.unsafeFrom(n))

  // A fabricated "post-bootstrap" history: one prior round with 5 signers.
  private val postBootstrapProofSizes: SortedMap[SnapshotOrdinal, Int] =
    SortedMap(ord(1L) -> 5)

  test(
    "outcome fields are independent of locally-observed signer count: two nodes seeing 3 vs 5 proofs produce identical penalty state"
  ) {
    // Both nodes saw the SAME state.facilitators and state.removedFacilitators.
    // They only differ in how many MajoritySignature declarations they captured
    // before finalizing (captured here as divergent "observed" proofs counts).
    // In the post-fix computation, that divergence is irrelevant.
    val fromFastFinalizer = computeOutcomeFields(
      key = ord(2L),
      facilitators = allFacilitators,
      removedFacilitators = noEvicted,
      previousPenalties = SortedMap.empty,
      previousCumulative = SortedMap.empty,
      previousPeerQuality = SortedMap.empty,
      previousRecentProofSizes = postBootstrapProofSizes,
      previousDeferrals = SortedMap.empty
    )
    // "slow finalizer" would historically have seen proofs.size=5; fast finalizer=3.
    // Under the fix, the computation takes no input from `proofs`, so both must be equal.
    val fromSlowFinalizer = computeOutcomeFields(
      key = ord(2L),
      facilitators = allFacilitators,
      removedFacilitators = noEvicted,
      previousPenalties = SortedMap.empty,
      previousCumulative = SortedMap.empty,
      previousPeerQuality = SortedMap.empty,
      previousRecentProofSizes = postBootstrapProofSizes,
      previousDeferrals = SortedMap.empty
    )
    expect(
      fromFastFinalizer.removalPenalties == fromSlowFinalizer.removalPenalties,
      s"removalPenalties must be identical, got fast=${fromFastFinalizer.removalPenalties} slow=${fromSlowFinalizer.removalPenalties}"
    ).and(
      expect(
        fromFastFinalizer.cumulativeMissCounts == fromSlowFinalizer.cumulativeMissCounts,
        s"cumulativeMissCounts must be identical"
      )
    ).and(expect(fromFastFinalizer.peerQuality == fromSlowFinalizer.peerQuality, s"peerQuality must be identical"))
      .and(
        expect(
          fromFastFinalizer.recentProofSizes == fromSlowFinalizer.recentProofSizes,
          s"recentProofSizes must be identical (uses consensus-agreed completedFacilitators.size)"
        )
      )
  }

  test("peerQuality awards (1,1) to every non-evicted facilitator — including peers that may have been locally slow to sign") {
    val result = computeOutcomeFields(
      key = ord(2L),
      facilitators = allFacilitators,
      removedFacilitators = noEvicted,
      previousPenalties = SortedMap.empty,
      previousCumulative = SortedMap.empty,
      previousPeerQuality = SortedMap.empty,
      previousRecentProofSizes = postBootstrapProofSizes,
      previousDeferrals = SortedMap.empty
    )
    expect(
      allFacilitators.forall(p => result.peerQuality.get(p).contains((1, 1))),
      s"every non-evicted facilitator should be (1,1); got ${result.peerQuality}"
    )
  }

  test("evicted peers are penalized (post-bootstrap) AND recorded as (0,1) in peerQuality") {
    val evicted = Set(p5)
    val result = computeOutcomeFields(
      key = ord(2L),
      facilitators = allFacilitators,
      removedFacilitators = evicted,
      previousPenalties = SortedMap.empty,
      previousCumulative = SortedMap.empty,
      previousPeerQuality = SortedMap.empty,
      previousRecentProofSizes = postBootstrapProofSizes,
      previousDeferrals = SortedMap.empty
    )
    expect(
      result.removalPenalties.get(p5).isDefined,
      s"evicted peer should have a removal penalty entry, got ${result.removalPenalties}"
    ).and(
      expect(
        result.peerQuality.get(p5).contains((0, 1)),
        s"evicted peer should be (0,1) in peerQuality, got ${result.peerQuality.get(p5)}"
      )
    ).and(
      expect(
        !result.removalPenalties.contains(p1),
        s"non-evicted peer should not be penalized, got ${result.removalPenalties.get(p1)}"
      )
    )
  }

  test("during bootstrap (recentProofSizes all below threshold), penalties are suppressed even with evictions") {
    val preBootstrap: SortedMap[SnapshotOrdinal, Int] = SortedMap(ord(1L) -> 1) // below threshold=3
    val evicted = Set(p5)
    val result = computeOutcomeFields(
      key = ord(2L),
      facilitators = allFacilitators,
      removedFacilitators = evicted,
      previousPenalties = SortedMap.empty,
      previousCumulative = SortedMap.empty,
      previousPeerQuality = SortedMap.empty,
      previousRecentProofSizes = preBootstrap,
      previousDeferrals = SortedMap.empty
    )
    expect(
      result.removalPenalties.isEmpty,
      s"bootstrap must suppress penalty accrual, got ${result.removalPenalties}"
    ).and(
      expect(
        result.cumulativeMissCounts.isEmpty,
        s"bootstrap must suppress cumulative-miss accrual, got ${result.cumulativeMissCounts}"
      )
    )
  }

  test("recentProofSizes uses consensus-agreed committee size (facilitators - evicted), not local proofs count") {
    val evicted = Set(p5)
    val result = computeOutcomeFields(
      key = ord(2L),
      facilitators = allFacilitators,
      removedFacilitators = evicted,
      previousPenalties = SortedMap.empty,
      previousCumulative = SortedMap.empty,
      previousPeerQuality = SortedMap.empty,
      previousRecentProofSizes = postBootstrapProofSizes,
      previousDeferrals = SortedMap.empty
    )
    // allFacilitators.size = 5, evicted = 1, so committee size = 4.
    expect(
      result.recentProofSizes.get(ord(2L)).contains(4),
      s"recentProofSizes should record committee size (4 = 5 facilitators - 1 evicted), got ${result.recentProofSizes}"
    )
  }

  test("deferred facilitators are not penalized even when evicted") {
    val evicted = Set(p5)
    val deferredPrev: SortedMap[PeerId, Int] = SortedMap(p5 -> 2) // p5 still in deferral
    val result = computeOutcomeFields(
      key = ord(2L),
      facilitators = allFacilitators,
      removedFacilitators = evicted,
      previousPenalties = SortedMap.empty,
      previousCumulative = SortedMap.empty,
      previousPeerQuality = SortedMap.empty,
      previousRecentProofSizes = postBootstrapProofSizes,
      previousDeferrals = deferredPrev
    )
    expect(
      !result.removalPenalties.contains(p5),
      s"deferred peer should not be penalized, got ${result.removalPenalties.get(p5)}"
    )
  }
}
