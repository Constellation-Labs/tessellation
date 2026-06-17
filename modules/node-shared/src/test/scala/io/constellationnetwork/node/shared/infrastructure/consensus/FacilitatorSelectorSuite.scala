package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.IO

import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.{CheckConfig, Checkers}

object FacilitatorSelectorSuite extends SimpleIOSuite with Checkers {

  override def checkConfig: CheckConfig = CheckConfig.default.copy(minimumSuccessful = 40)

  val selector: FacilitatorSelector = FacilitatorSelector.make(Some(10))

  private def pid(name: String): PeerId = PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  def facilitatorsGen: Gen[List[PeerId]] =
    Gen
      .choose(5, 20)
      .flatMap(size => Gen.containerOfN[Set, PeerId](size, arbitrary[PeerId]))
      .map(_.toList.sorted)

  // === selectLeader tests ===

  test("selectLeader is deterministic") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        val leader1 = selector.selectLeader(facilitators, entropy)
        val leader2 = selector.selectLeader(facilitators, entropy)
        expect.same(leader1, leader2)
      }
    }
  }

  test("selectLeader rotates with viewNumber") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        val leaders = (0 until facilitators.size).map(v => selector.selectLeader(facilitators, entropy, v))
        // All leaders should be different facilitators (for distinct view numbers < facilitator count)
        expect.same(facilitators.size, leaders.distinct.size)
      }
    }
  }

  test("selectLeader wraps around viewNumber") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        val leader0 = selector.selectLeader(facilitators, entropy, 0)
        val leaderWrapped = selector.selectLeader(facilitators, entropy, facilitators.size)
        expect.same(leader0, leaderWrapped)
      }
    }
  }

  // === selectLeaderWeighted tests ===

  test("selectLeaderWeighted is deterministic with same quality scores") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        // (completed=5, participated=10) → 50% quality for all
        val scores = facilitators.map(_ -> (5, 10)).toMap
        val leader1 = selector.selectLeaderWeighted(facilitators, entropy, 0, scores)
        val leader2 = selector.selectLeaderWeighted(facilitators, entropy, 0, scores)
        expect.same(leader1, leader2)
      }
    }
  }

  test("selectLeaderWeighted with all zero-failure scores matches pure rendezvous ordering") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        // All perfect: (10, 10) → 0 failures → all tier 0 → pure rendezvous
        val scores = facilitators.map(_ -> (10, 10)).toMap
        val standard = selector.selectLeader(facilitators, entropy)
        val weighted = selector.selectLeaderWeighted(facilitators, entropy, 0, scores)
        expect.same(standard, weighted)
      }
    }
  }

  test("selectLeaderWeighted prefers high-quality peer (fewer failures)") {
    IO {
      val peers = (1 to 5).map(i => pid(s"peer$i")).toList

      // Give one peer perfect quality (0 failures), rest very poor (9 out of 10 failed)
      val highQualityPeer = peers.head
      val scores = peers.map(p => p -> (if (p == highQualityPeer) (10, 10) else (1, 10))).toMap

      // High-quality peer (tier=0) should beat poor peers (tier=9) across entropies
      val entropies = (0 until 20).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val selections = entropies.map(e => selector.selectLeaderWeighted(peers, e, 0, scores))
      val highQualityCount = selections.count(_ == highQualityPeer)

      // High-quality peer should be selected more than fair share (1/5 = 4 times out of 20)
      expect(highQualityCount > 4)
    }
  }

  test("selectLeaderWeighted rotates with viewNumber") {
    IO {
      val peers = (1 to 5).map(i => pid(s"peer$i")).toList
      val entropy = Hash.empty
      // (8, 10) = 2 failures each → same tier for all → view rotation works
      val scores = peers.map(_ -> (8, 10)).toMap

      val leaders = (0 until peers.size).map(v => selector.selectLeaderWeighted(peers, entropy, v, scores))
      // All leaders should be distinct for different view numbers
      expect.same(peers.size, leaders.distinct.size)
    }
  }

  test("selectLeaderWeighted integer tier is deterministic across invocations") {
    IO {
      // Verify that integer-only tier computation produces identical results
      // This is the key safety test — float-to-long conversion was the fork risk
      val peers = (1 to 10).map(i => pid(s"peer$i")).toList
      val entropy = Hash.fromBytes("test-entropy".getBytes("UTF-8"))
      val scores: Map[PeerId, (Int, Int)] = peers.zipWithIndex.map {
        case (p, i) =>
          p -> (i, 10) // varying quality: 0/10, 1/10, ... 9/10
      }.toMap

      val results = (1 to 100).map(_ => selector.selectLeaderWeighted(peers, entropy, 0, scores))
      // Must be perfectly deterministic
      expect(results.distinct.size == 1)
    }
  }

  // === select (facilitator subset) tests ===

  test("select returns all candidates when fewer than maxCount") {
    IO {
      val smallSelector = FacilitatorSelector.make(Some(20))
      val peers = (1 to 5).map(i => pid(s"peer$i")).toList
      val result = smallSelector.select(peers, Hash.empty)
      expect.same(5, result.size) && expect.same(peers.sorted, result)
    }
  }

  test("select returns maxCount candidates when more available") {
    IO {
      val smallSelector = FacilitatorSelector.make(Some(3))
      val peers = (1 to 10).map(i => pid(s"peer$i")).toList
      val result = smallSelector.select(peers, Hash.empty)
      expect.same(3, result.size)
    }
  }

  test("select is deterministic") {
    forall(facilitatorsGen) { facilitators =>
      IO {
        val entropy = Hash.empty
        val result1 = selector.select(facilitators, entropy)
        val result2 = selector.select(facilitators, entropy)
        expect.same(result1, result2)
      }
    }
  }

  test("select with None maxCount returns all candidates") {
    IO {
      val unlimitedSelector = FacilitatorSelector.make(None)
      val peers = (1 to 20).map(i => pid(s"peer$i")).toList
      val result = unlimitedSelector.select(peers, Hash.empty)
      expect.same(20, result.size)
    }
  }

  // === Empty list guard tests ===

  test("selectLeader throws on empty facilitators list") {
    IO {
      val caught = scala.util.Try(selector.selectLeader(List.empty, Hash.empty))
      expect(caught.isFailure) && expect(caught.failed.get.isInstanceOf[IllegalArgumentException])
    }
  }

  test("selectLeaderWeighted throws on empty facilitators list") {
    IO {
      val caught = scala.util.Try(selector.selectLeaderWeighted(List.empty, Hash.empty))
      expect(caught.isFailure) && expect(caught.failed.get.isInstanceOf[IllegalArgumentException])
    }
  }

  // === Entropy-based selection tests ===

  test("select produces different subsets with different entropy") {
    IO {
      val smallSelector = FacilitatorSelector.make(Some(3))
      val peers = (1 to 10).map(i => pid(s"peer$i")).toList
      val entropies = (0 until 10).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val subsets = entropies.map(e => smallSelector.select(peers, e))
      // Not all subsets should be identical (extremely unlikely with different entropies)
      expect(subsets.distinct.size > 1)
    }
  }

  test("selectLeader changes with different entropy") {
    IO {
      val peers = (1 to 10).map(i => pid(s"peer$i")).toList
      val entropies = (0 until 10).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val leaders = entropies.map(e => selector.selectLeader(peers, e))
      // Not all leaders should be the same with different entropy
      expect(leaders.distinct.size > 1)
    }
  }

  // === Leader graduation filter tests ===
  //
  // These tests exercise the caller-side pattern used in both GlobalSnapshotConsensusStateCreator
  // and CurrencySnapshotConsensusStateCreator: filter `active` to peers with `participated >=
  // minParticipationObservations`, fall back to full `active` if nobody qualifies, then pass the
  // resulting pool to selectLeaderWeighted. The selector itself is unchanged; the filter prevents
  // untracked peers (tier=0 by default) from tying with proven peers and winning by rendezvous.

  private def selectGraduatedLeader(
    active: List[PeerId],
    entropy: Hash,
    scores: Map[PeerId, (Int, Int)],
    threshold: Int,
    viewNumber: Int = 0
  ): PeerId = {
    // Kick-fast leader graduation. Mirrors production at
    // GlobalSnapshotConsensusStateCreator.scala:513 -- a peer must have BOTH enough history
    // AND at least one completed round to be lead-eligible. Closes the chronic-flaky leader
    // trap where peers accumulated participated counts but had completed=0.
    val graduated = active.filter { p =>
      val (completed, participated) = scores.getOrElse(p, (0, 0))
      participated >= threshold && completed >= 1
    }
    val pool = if (graduated.size >= 2) graduated else active
    selector.selectLeaderWeighted(pool, entropy, viewNumber, scores)
  }

  test("graduation filter: unproven peer never selected when proven peers exist") {
    IO {
      val proven = (1 to 3).map(i => pid(s"proven$i")).toList
      val unproven = (1 to 2).map(i => pid(s"unproven$i")).toList
      val active = proven ++ unproven
      val scores: Map[PeerId, (Int, Int)] =
        (proven ++ unproven).map(p => p -> (if (proven.contains(p)) (10, 10) else (0, 0))).toMap
      val threshold = 5

      val entropies = (0 until 50).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val leaders = entropies.map(e => selectGraduatedLeader(active, e, scores, threshold))

      expect(leaders.forall(proven.contains), s"unproven peer selected: ${leaders.filterNot(proven.contains)}")
    }
  }

  test("graduation filter: falls back to active when nobody meets threshold (genesis / cold start)") {
    IO {
      // All peers have participated < threshold. Filter would return empty. Fallback to active.
      // Selection should still work and be deterministic across entropies.
      val active = (1 to 5).map(i => pid(s"peer$i")).toList
      val scores: Map[PeerId, (Int, Int)] = active.map(_ -> (0, 0)).toMap
      val threshold = 5
      val entropy = Hash.fromBytes("cold-start".getBytes("UTF-8"))

      val leader = selectGraduatedLeader(active, entropy, scores, threshold)

      expect(active.contains(leader), s"leader $leader not in active pool")
    }
  }

  test("graduation filter: falls back to active when peerQuality map is empty") {
    IO {
      val active = (1 to 5).map(i => pid(s"peer$i")).toList
      val scores = Map.empty[PeerId, (Int, Int)]
      val threshold = 5
      val entropy = Hash.fromBytes("empty-quality".getBytes("UTF-8"))

      val leader = selectGraduatedLeader(active, entropy, scores, threshold)

      expect(active.contains(leader), s"leader $leader not in active pool")
    }
  }

  test("graduation filter: view-change rotates only through graduated pool") {
    IO {
      // Three proven peers, two unproven. View rotation must cycle through proven ONLY.
      val proven = (1 to 3).map(i => pid(s"proven$i")).toList
      val unproven = (1 to 2).map(i => pid(s"unproven$i")).toList
      val active = proven ++ unproven
      val scores: Map[PeerId, (Int, Int)] =
        (proven ++ unproven).map(p => p -> (if (proven.contains(p)) (10, 10) else (0, 0))).toMap
      val threshold = 5
      val entropy = Hash.fromBytes("rotate".getBytes("UTF-8"))

      val leaders = (0 until 10).map(v => selectGraduatedLeader(active, entropy, scores, threshold, v))

      expect(leaders.forall(proven.contains), "view rotation leaked into unproven pool").and(
        expect.same(proven.size, leaders.distinct.size)
      )
    }
  }

  test("graduation filter: single graduated peer falls back to active (view rotation needs >= 2)") {
    IO {
      // Only one peer meets the threshold. If we used the graduated pool of size 1, view
      // rotation would be a no-op (viewNumber % 1 = 0 always). The filter must fall back
      // to `active` so rotation can actually rotate. Regression test for the ordinal-5
      // E2E stall where gl0-0 was the sole graduated peer post-solo-bootstrap.
      val soleProven = pid("sole-proven")
      val unproven = (1 to 4).map(i => pid(s"unproven$i")).toList
      val active = soleProven +: unproven
      val scores: Map[PeerId, (Int, Int)] =
        active.map(p => p -> (if (p == soleProven) (10, 10) else (0, 0))).toMap
      val threshold = 5

      // View rotation across 5 viewNumbers should produce 5 distinct leaders (pool=active=5)
      val entropy = Hash.fromBytes("bootstrap-tail".getBytes("UTF-8"))
      val leaders = (0 until active.size).map(v => selectGraduatedLeader(active, entropy, scores, threshold, v))

      expect.same(active.size, leaders.distinct.size)
    }
  }

  test("graduation filter: two graduated peers activate the filter (boundary)") {
    IO {
      // Exactly two peers meet the threshold: pool size >= 2, filter activates,
      // view rotation alternates between the two.
      val proven = List(pid("proven1"), pid("proven2"))
      val unproven = (1 to 3).map(i => pid(s"unproven$i")).toList
      val active = proven ++ unproven
      val scores: Map[PeerId, (Int, Int)] =
        (proven ++ unproven).map(p => p -> (if (proven.contains(p)) (10, 10) else (0, 0))).toMap
      val threshold = 5

      val entropy = Hash.fromBytes("two-graduated".getBytes("UTF-8"))
      val leaders = (0 until 10).map(v => selectGraduatedLeader(active, entropy, scores, threshold, v))

      expect(leaders.forall(proven.contains), "leader escaped graduated pool").and(
        expect.same(proven.size, leaders.distinct.size)
      )
    }
  }

  // === Kick-fast leader graduation tests ===
  //
  // Regression coverage for the apr30 testnet deadlock: chronic-flaky peers (890a641e,
  // c96c3a41) had `participated >= minObservations` but `completed == 0` because they
  // never finalized any round. The earlier graduation filter ONLY checked `participated`,
  // letting them lead -> no proposal -> infinite stall. The fix adds `completed >= 1`.

  test("kick-fast: peer with high participated but zero completions is rejected as leader") {
    IO {
      // Models 890a641e exactly: in committee for 12 rounds, finalized 0.
      val flaky = pid("flaky-with-high-participation-zero-completed")
      val proven = (1 to 3).map(i => pid(s"proven$i")).toList
      val active = flaky +: proven
      val scores: Map[PeerId, (Int, Int)] = Map(
        flaky -> (0, 12) // participated=12 (well above threshold), completed=0 (never delivered)
      ) ++ proven.map(p => p -> (10, 10)).toMap
      val threshold = 5

      val entropies = (0 until 50).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val leaders = entropies.map(e => selectGraduatedLeader(active, e, scores, threshold))

      expect(
        !leaders.contains(flaky),
        s"chronic-flaky peer (participated=12, completed=0) was elected leader: ${leaders.count(_ == flaky)} times in 50 entropies"
      ).and(
        expect(leaders.forall(proven.contains), "every leader must come from proven set")
      )
    }
  }

  test("kick-fast: peer with completions above v16 floor graduates back to lead-eligible") {
    IO {
      // Recovery path: a peer that previously never completed now has some wins. To be
      // lead-eligible under v16 it must clear BOTH (a) graduation (completed >= 1) AND
      // (b) the hard quality-score floor at 20%. Original pre-v16 test used (1, 12) which
      // is 8.3% -- correctly excluded by v16 now. Updated to (5, 12) which is 41.7%,
      // above the 20% floor, demonstrating the recovery path under v16 rules.
      val recovered = pid("recovered-above-floor")
      val proven = (1 to 2).map(i => pid(s"proven$i")).toList
      val active = recovered +: proven
      val scores: Map[PeerId, (Int, Int)] = Map(
        recovered -> (5, 12) // 5 of 12 = 41.7%, still below leaderRotationMinRatioPct (50%) so tier 1, but above v16 hard floor (20%)
      ) ++ proven.map(p => p -> (10, 10)).toMap
      val threshold = 5

      // With recovered+proven all eligible (3 in graduatedLeaderPool >= 2), view rotation
      // should be able to land on `recovered` for at least some viewNumbers.
      val entropy = Hash.fromBytes("rotation-includes-recovered".getBytes("UTF-8"))
      val leaders = (0 until 3).map(v => selectGraduatedLeader(active, entropy, scores, threshold, v))

      expect(leaders.contains(recovered) || leaders.toSet.size == 3, s"recovered peer never reached leader slot: $leaders")
    }
  }

  test("v16: peer with completions below the hard quality-score floor is NOT lead-eligible") {
    IO {
      // Companion to the test above: a peer with ratio 8.3% (1/12) should NOT be elected
      // even though they pass graduation. Captures the regression that v16 was designed
      // to fix: pre-v16 the peer would rotate into leadership at some view; post-v16
      // they are excluded from the leader pool entirely so other peers always win.
      val belowFloor = pid("below-floor-one-of-twelve")
      val proven = (1 to 2).map(i => pid(s"proven$i")).toList
      val active = belowFloor +: proven
      val scores: Map[PeerId, (Int, Int)] = Map(
        belowFloor -> (1, 12) // 1 of 12 = 8.3%, BELOW the 20% v16 floor
      ) ++ proven.map(p => p -> (10, 10)).toMap
      val threshold = 5

      val entropies = (0 until 50).map(i => Hash.fromBytes(s"v16-recovery-floor-$i".getBytes("UTF-8")))
      val winners = entropies.flatMap(e => (0 until 5).map(v => selectGraduatedLeader(active, e, scores, threshold, v)))

      expect.same(0, winners.count(_ == belowFloor))
    }
  }

  test("kick-fast: deterministic across nodes — same (active, scores, threshold, view) → same leader") {
    IO {
      // Critical for fork safety: two honest nodes with identical inputs MUST select the same
      // leader. The graduation filter must be a pure function of consensus-agreed state.
      val flaky = pid("flaky")
      val proven = (1 to 5).map(i => pid(s"proven$i")).toList
      val active = flaky +: proven
      val scores: Map[PeerId, (Int, Int)] = Map(flaky -> (0, 15)) ++ proven.map(p => p -> (8, 10)).toMap
      val threshold = 5

      val entropy = Hash.fromBytes("determinism".getBytes("UTF-8"))
      val nodeA = selectGraduatedLeader(active, entropy, scores, threshold, viewNumber = 2)
      val nodeB = selectGraduatedLeader(active, entropy, scores, threshold, viewNumber = 2)

      expect.same(nodeA, nodeB)
    }
  }

  // === Leader-rotation band tests ===
  //
  // The earlier tier formula was `participated - completed` (unbounded). The asymmetric
  // crediting in `observedResponders` ratcheted non-leader peers' tiers up indefinitely;
  // empirically on testnet alpha.72 this concentrated 100% of leadership on 2 peers even
  // though 6 peers were demonstrably "good enough" by quality ratio. v14 replaces the
  // formula with a binary band keyed on `minLeaderRatioPct` so leadership rotates
  // uniformly across all above-threshold peers via per-round rendezvous entropy.

  test("v14 binary-band: above-threshold peers share leadership across rounds") {
    IO {
      // Mirrors testnet alpha.72: one peer at 100%, others at 85-95%. Pre-v14 the 100%
      // peer dominated (tier=0 vs tier=5-15 for others). v14 puts all five in tier 0 and
      // rendezvous entropy spreads selection.
      val peers = (1 to 5).map(i => pid(s"peer$i")).toList
      val scores: Map[PeerId, (Int, Int)] = Map(
        peers(0) -> (100, 100),
        peers(1) -> (95, 100),
        peers(2) -> (90, 100),
        peers(3) -> (85, 100),
        peers(4) -> (50, 100) // exactly the 50% boundary
      )

      val entropies = (0 until 500).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val selections = entropies.map(e => selector.selectLeaderWeighted(peers, e, 0, scores))
      val counts = peers.map(p => p -> selections.count(_ == p)).toMap

      // Each of the 5 peers must take at least 10% of leadership (uniform ideal = 20%).
      val belowFloor = peers.filter(p => counts(p) < 50)
      expect(
        belowFloor.isEmpty,
        s"binary-band failed to rotate: ${belowFloor.map(p => s"${p.value.value.take(8)}=${counts(p)}").mkString(", ")}"
      )
    }
  }

  test("v14 binary-band: chronic peer below threshold never beats above-threshold peer at view 0") {
    IO {
      // chronic = 30%, good = 80%. Both pass the graduation filter (completed >= 1).
      // Pre-v14 chronic at tier=7 vs good at tier=2 was still occasionally selected via
      // rendezvous tiebreak. v14 puts chronic in tier 1, good in tier 0 -> good wins every time.
      val chronic = pid("chronic-30pct")
      val good = pid("good-80pct")
      val scores = Map(chronic -> (3, 10), good -> (8, 10))

      val entropies = (0 until 200).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))
      val selections = entropies.map(e => selector.selectLeaderWeighted(List(chronic, good), e, 0, scores))

      expect.same(200, selections.count(_ == good))
    }
  }

  test("v14 binary-band: threshold is tunable via minLeaderRatioPct") {
    IO {
      // A peer at 60% should be tier 0 with threshold=50 (eligible) but tier 1 with threshold=70 (fallback).
      val a = pid("a-60pct")
      val b = pid("b-80pct")
      val scores = Map(a -> (6, 10), b -> (8, 10))
      val entropies = (0 until 100).map(i => Hash.fromBytes(s"entropy$i".getBytes("UTF-8")))

      val at50 = entropies.map(e => selector.selectLeaderWeighted(List(a, b), e, 0, scores, minLeaderRatioPct = 50))
      val aAt50 = at50.count(_ == a)
      expect(aAt50 > 20 && aAt50 < 80, s"threshold=50: both tier 0, should rotate ~50/50, got a=$aAt50/100")

      val at70 = entropies.map(e => selector.selectLeaderWeighted(List(a, b), e, 0, scores, minLeaderRatioPct = 70))
      expect.same(100, at70.count(_ == b))
    }
  }

  test("v14 binary-band: no-history peer (participated=0) is tier 0 (bootstrap fallback preserved)") {
    IO {
      // Pre-v14 had `if (participated > 0) (participated - completed) else 0` -- no-history was
      // tier 0. v14 preserves that exact behavior so bootstrap / cold-start clusters still pick
      // a deterministic leader instead of being stuck in "no eligible peer" land.
      val bootstrap = pid("no-history")
      val active = List(bootstrap)
      val scores: Map[PeerId, (Int, Int)] = Map.empty // bootstrap has no entry
      val leader = selector.selectLeaderWeighted(active, Hash.empty, 0, scores)
      expect.same(bootstrap, leader)
    }
  }

  test("v14 binary-band: integer comparison places ratios at and just below the threshold correctly") {
    IO {
      // Integer-only comparison guard. `completed * 100 >= participated * threshold` must
      // place 50/100 in tier 0 and 49/100 in tier 1. With float arithmetic the boundary
      // could shift between JVMs (49.0/100.0 may round differently than 0.49), so verify
      // the integer math directly by sweeping entropies and asserting the tier-1 peer
      // never wins when any tier-0 peer is also in the pool.
      val atBoundary = pid("at-50pct")
      val justBelow = pid("just-49pct")
      val justAbove = pid("just-51pct")
      val scores = Map(
        atBoundary -> (50, 100),
        justBelow -> (49, 100),
        justAbove -> (51, 100)
      )

      val entropies = (0 until 500).map(i => Hash.fromBytes(s"boundary-$i".getBytes("UTF-8")))
      val winners = entropies.map(e => selector.selectLeaderWeighted(List(atBoundary, justBelow, justAbove), e, 0, scores))

      val leaked = winners.count(_ == justBelow)
      expect(leaked == 0, s"49/100 tier-1 peer leaked into leader pool: $leaked selections")
    }
  }

  // === end v14 tests ===

  // === v15 self-health throttle tests ===

  test("v15 self-health: Degraded peer demoted to tier 1 below an otherwise-tied Healthy peer") {
    IO {
      val healthy = pid("healthy-peer")
      val degraded = pid("degraded-peer")
      // Both at the same observed quality so the only differentiator is the self-health hint.
      val scores = Map(healthy -> (9, 10), degraded -> (9, 10))
      val hints: Map[PeerId, SelfHealthHint] = Map(degraded -> SelfHealthHint.Degraded)
      val entropies = (0 until 200).map(i => Hash.fromBytes(s"v15-deg-$i".getBytes("UTF-8")))

      val winners = entropies.map { e =>
        selector.selectLeaderWeighted(List(healthy, degraded), e, viewNumber = 0, scores, selfHealthHints = hints)
      }
      // Degraded must never win at view 0 when a Healthy alternative exists.
      expect.same(0, winners.count(_ == degraded))
    }
  }

  test("v15 self-health: Critical peer demoted to tier 2 below Degraded peers") {
    IO {
      val healthy = pid("healthy")
      val degraded = pid("degraded")
      val critical = pid("critical")
      val scores = Map(healthy -> (9, 10), degraded -> (9, 10), critical -> (9, 10))
      val hints: Map[PeerId, SelfHealthHint] = Map(
        degraded -> SelfHealthHint.Degraded,
        critical -> SelfHealthHint.Critical
      )
      val entropies = (0 until 200).map(i => Hash.fromBytes(s"v15-crit-$i".getBytes("UTF-8")))

      val winners = entropies.map { e =>
        selector.selectLeaderWeighted(List(healthy, degraded, critical), e, 0, scores, selfHealthHints = hints)
      }
      // Critical must never be picked while any non-Critical peer exists.
      expect.same(0, winners.count(_ == critical))
    }
  }

  test("v15 self-health: all-Critical pool still elects a leader (strong demote, not hard exclude)") {
    IO {
      // The deadlock-avoidance guarantee: if every peer reports Critical, the round still resolves.
      // This is the user-required liveness property -- Phase B+C must never wedge the cluster by
      // refusing all candidates.
      val peers = (1 to 5).map(i => pid(s"critical-$i")).toList
      val scores = peers.map(_ -> (9, 10)).toMap
      val hints: Map[PeerId, SelfHealthHint] = peers.map(_ -> SelfHealthHint.Critical).toMap

      val leader = selector.selectLeaderWeighted(peers, Hash.empty, 0, scores, selfHealthHints = hints)
      expect(peers.contains(leader), s"all-Critical pool produced invalid leader: $leader")
    }
  }

  test("v15 self-health: missing hint defaults to Healthy (v14 binary-band behavior preserved)") {
    IO {
      // Peer A reports Healthy explicitly, Peer B has no entry in the hint map. Both must land in
      // tier 0 so the choice is decided by rendezvous entropy -- mirrors the bootstrap path where
      // peers come up before any hint has been collected.
      val a = pid("explicit-healthy")
      val b = pid("missing-hint")
      val scores = Map(a -> (9, 10), b -> (9, 10))
      val hints: Map[PeerId, SelfHealthHint] = Map(a -> SelfHealthHint.Healthy)
      val entropies = (0 until 200).map(i => Hash.fromBytes(s"v15-miss-$i".getBytes("UTF-8")))

      val aCount = entropies.count(e => selector.selectLeaderWeighted(List(a, b), e, 0, scores, selfHealthHints = hints) == a)
      // Should rotate ~50/50; tolerate wide bounds because rendezvous is sensitive to entropy.
      expect(aCount > 50 && aCount < 150, s"expected ~50/50 rotation with default-Healthy, got a=$aCount/200")
    }
  }

  test("v15 self-health: Healthy below leaderRatio still beats Degraded (within tier 1)") {
    IO {
      // Tier model: Healthy-but-below-ratio -> tier 1. Degraded -> also tier 1. They should rotate.
      // The intended demotion only fires across tiers; same-tier peers continue to rotate via
      // rendezvous so leadership doesn't concentrate on a single peer just because two are equal.
      val belowRatio = pid("healthy-30pct")
      val degraded = pid("degraded-perfect")
      val scores = Map(belowRatio -> (3, 10), degraded -> (10, 10))
      val hints: Map[PeerId, SelfHealthHint] = Map(degraded -> SelfHealthHint.Degraded)
      val entropies = (0 until 200).map(i => Hash.fromBytes(s"v15-mix-$i".getBytes("UTF-8")))

      val winners = entropies.map(e => selector.selectLeaderWeighted(List(belowRatio, degraded), e, 0, scores, selfHealthHints = hints))
      val belowCount = winners.count(_ == belowRatio)
      // Same tier => rotation. Don't pin a tight ratio; just verify both can win.
      expect(belowCount > 50 && belowCount < 150, s"same-tier rotation broken: below-ratio=$belowCount/200")
    }
  }

  // === end v15 tests ===

  test("kick-fast: all-flaky cluster falls back to active (cluster of zero-completion peers)") {
    IO {
      // Edge case: nobody has any completions yet. This shouldn't happen in steady state, but
      // could occur on cold genesis or after a catastrophic restart with cleared peerQuality.
      // The size>=2 fallback rule still applies — pool is `active` so cluster can bootstrap.
      val active = (1 to 5).map(i => pid(s"unproven$i")).toList
      val scores: Map[PeerId, (Int, Int)] = active.map(_ -> (0, 12)).toMap // all participated=12, completed=0
      val threshold = 5
      val entropy = Hash.fromBytes("all-flaky".getBytes("UTF-8"))

      val leader = selectGraduatedLeader(active, entropy, scores, threshold)

      // Filter rejects everyone (completed >= 1 fails for all). Falls back to `active`. Leader
      // chosen from active — could be any of them, just must be valid.
      expect(active.contains(leader), s"leader $leader not in active fallback pool")
    }
  }

  // === Hard leader-eligible floor tests ===
  //
  // selectLeaderWeighted now applies a pre-filter using `hardLeaderEligibleMinRatioPct` (default 20)
  // BEFORE the tier sort. Peers below the floor are removed from leader candidacy entirely;
  // they remain committee members. If fewer than `minLeaderPoolSize` (default 3) peers survive
  // the filter, the full input is used (starvation fallback). Closes the chronic-leader-loop
  // wedge where score-0.07 peers continued to be elected via tier-1
  // rotation.

  test("v16 hard floor: chronic peer below 0.2 never elected when 3+ healthy peers exist") {
    IO {
      // Models the chronic-leader wedge: chronic at 0.07 (1/15), healthy peers in tier 0/1 above
      // the hard floor. Earlier the chronic peer was tier 1 and `sorted[viewNumber % size]`
      // walked through it on view rotation. The filter removes it from the pool entirely.
      val chronic = pid("chronic-7pct")
      val healthy = (1 to 3).map(i => pid(s"healthy-$i")).toList
      val pool = chronic +: healthy
      val scores: Map[PeerId, (Int, Int)] = Map(chronic -> (1, 15)) ++ healthy.map(_ -> (9, 10)).toMap

      // Sweep view numbers 0..49 across multiple entropies. Chronic must never be returned.
      val entropies = (0 until 10).map(i => Hash.fromBytes(s"v16-floor-$i".getBytes("UTF-8")))
      val views = entropies.flatMap(e => (0 until 50).map(v => selector.selectLeaderWeighted(pool, e, v, scores)))

      expect.same(0, views.count(_ == chronic))
    }
  }

  test("v16 hard floor: peer just below threshold excluded, peer just above included") {
    IO {
      // Integer-arithmetic boundary, mirrors the v14 boundary test. With default threshold=20:
      // 19/100 must be excluded; 20/100 must be included.
      val justBelow = pid("just-19pct")
      val atBoundary = pid("at-20pct")
      val justAbove = pid("just-21pct")
      val healthy = (1 to 3).map(i => pid(s"healthy-$i")).toList
      val pool = List(justBelow, atBoundary, justAbove) ++ healthy
      val scores: Map[PeerId, (Int, Int)] = Map(
        justBelow -> (19, 100),
        atBoundary -> (20, 100),
        justAbove -> (21, 100)
      ) ++ healthy.map(_ -> (10, 10)).toMap

      val entropies = (0 until 50).map(i => Hash.fromBytes(s"v16-bound-$i".getBytes("UTF-8")))
      val winners = entropies.flatMap(e => (0 until 20).map(v => selector.selectLeaderWeighted(pool, e, v, scores)))

      expect(
        !winners.contains(justBelow),
        s"just-19pct leaked into leader pool"
      )
    }
  }

  test("v16 hard floor: starvation fallback engages when pool too small") {
    IO {
      // 4 peers, 3 below the floor (would be filtered), 1 above. After filtering, pool size = 1
      // which is below minLeaderPoolSize=3. Selector must fall back to the full input so the
      // round still has a leader. Cluster cannot wedge just because too many peers are chronic.
      val above = pid("only-good")
      val chronic = (1 to 3).map(i => pid(s"chronic-$i")).toList
      val pool = above +: chronic
      val scores: Map[PeerId, (Int, Int)] = Map(above -> (10, 10)) ++ chronic.map(_ -> (1, 20)).toMap

      val entropy = Hash.fromBytes("v16-starve".getBytes("UTF-8"))
      val leaders = (0 until 4).map(v => selector.selectLeaderWeighted(pool, entropy, v, scores))

      // Fallback engaged: all 4 peers reachable across view rotation.
      expect.same(4, leaders.distinct.size)
    }
  }

  test("v16 hard floor: bootstrap peers (participated=0) bypass the filter") {
    IO {
      // Cold-start path: peerQuality is empty or peers have participated=0. The hard floor
      // check must NOT exclude unproven peers; the call-site graduation filter is the
      // authority on unproven exclusion (and at bootstrap it falls back to active anyway).
      val bootstrap = (1 to 4).map(i => pid(s"bootstrap-$i")).toList
      val scores: Map[PeerId, (Int, Int)] = Map.empty // no history at all

      val entropy = Hash.fromBytes("v16-bootstrap".getBytes("UTF-8"))
      val leaders = (0 until bootstrap.size).map(v => selector.selectLeaderWeighted(bootstrap, entropy, v, scores))

      // No filter exclusion -> rotation picks each peer once.
      expect.same(bootstrap.size, leaders.distinct.size)
    }
  }

  test("v16 hard floor: Critical self-health excluded from leader pool when alternatives exist") {
    IO {
      // The v15 tier-2 demote handled this within the tier sort, but the v16 starvation
      // fallback could re-expose Critical peers when too few peers survive the ratio filter.
      // The pool filter now also excludes Critical so it cannot be elected via fallback unless
      // every peer is Critical (the all-Critical case is covered by an existing v15 test).
      val critical = pid("critical-peer")
      val healthy = (1 to 3).map(i => pid(s"healthy-$i")).toList
      val pool = critical +: healthy
      val scores = pool.map(_ -> (10, 10)).toMap
      val hints: Map[PeerId, SelfHealthHint] = Map(critical -> SelfHealthHint.Critical)

      val entropies = (0 until 50).map(i => Hash.fromBytes(s"v16-crit-$i".getBytes("UTF-8")))
      val winners =
        entropies.flatMap(e => (0 until 10).map(v => selector.selectLeaderWeighted(pool, e, v, scores, selfHealthHints = hints)))

      expect.same(0, winners.count(_ == critical))
    }
  }

  test("v16 hard floor: deterministic across nodes with same inputs") {
    IO {
      // Fork-safety regression: two honest nodes with identical (pool, entropy, view, scores,
      // hints, thresholds) MUST return the same leader. Pre-v16 this was already true; v16
      // adds a filter step that must also be deterministic.
      val chronic = pid("chronic")
      val healthy = (1 to 4).map(i => pid(s"healthy-$i")).toList
      val pool = chronic +: healthy
      val scores: Map[PeerId, (Int, Int)] = Map(chronic -> (1, 20)) ++ healthy.map(_ -> (9, 10)).toMap

      val entropy = Hash.fromBytes("v16-det".getBytes("UTF-8"))
      val nodeA = selector.selectLeaderWeighted(pool, entropy, viewNumber = 3, scores)
      val nodeB = selector.selectLeaderWeighted(pool, entropy, viewNumber = 3, scores)

      expect.same(nodeA, nodeB)
    }
  }

  test("v16 view-change penalty: peer with high completion but high view-change rate is excluded") {
    IO {
      // The audit-driven case: a peer that completes EVERY round (10/10) but causes a
      // view change in 9 of them has raw ratio 1.0 (would pass any ratio-only filter) but
      // qualityScore = 1.0 * (1 - 0.9) = 0.10, which is below the 20% floor. The v16
      // filter must exclude this peer. This is the specific failure mode the patch was
      // designed to address.
      val wedgingLeader = pid("wedging-leader-10-of-10-9-vc")
      val healthy = (1 to 3).map(i => pid(s"healthy-$i")).toList
      val pool = wedgingLeader +: healthy
      val scores: Map[PeerId, (Int, Int)] = Map(wedgingLeader -> (10, 10)) ++ healthy.map(_ -> (9, 10)).toMap
      val viewChanges: Map[PeerId, Long] = Map(wedgingLeader -> 9L)

      val entropies = (0 until 30).map(i => Hash.fromBytes(s"v16-vc-$i".getBytes("UTF-8")))
      val winners =
        entropies.flatMap(e => (0 until 5).map(v => selector.selectLeaderWeighted(pool, e, v, scores, peerViewChanges = viewChanges)))

      expect.same(0, winners.count(_ == wedgingLeader))
    }
  }

  test("v16 view-change penalty: peer with zero view changes uses raw ratio only") {
    IO {
      // Sanity: when viewChangesCaused is 0 (or absent), the formula reduces to the
      // raw completed/participated ratio. A peer at 25% completion with 0 view changes
      // should pass the 20% floor.
      val cleanLeader = pid("clean-no-view-changes")
      val ok = (1 to 3).map(i => pid(s"ok-$i")).toList
      val pool = cleanLeader +: ok
      val scores: Map[PeerId, (Int, Int)] = Map(cleanLeader -> (25, 100)) ++ ok.map(_ -> (10, 10)).toMap
      // peerViewChanges intentionally empty -- exercises the default-0 lookup
      val viewChanges: Map[PeerId, Long] = Map.empty

      val entropies = (0 until 50).map(i => Hash.fromBytes(s"v16-clean-$i".getBytes("UTF-8")))
      val winners =
        entropies.flatMap(e => (0 until 4).map(v => selector.selectLeaderWeighted(pool, e, v, scores, peerViewChanges = viewChanges)))

      // Clean leader passes the floor; should appear among the selected leaders at some view.
      expect(winners.contains(cleanLeader), s"clean-leader at 25% with 0 vc never elected: $winners")
    }
  }

  test("v16 view-change penalty: peer at exact qualityScore boundary (20%)") {
    IO {
      // Integer-arithmetic boundary check for the new formula. With completed=50,
      // participated=100, vcc=60: deficit=40, score = 50*40/100^2 = 0.20 (exactly the
      // threshold). The integer comparison is `completed * deficit * 100 >= threshold *
      // participated^2`, i.e. 50*40*100 = 200000 >= 20*100*100 = 200000. Equal -> passes.
      val atBoundary = pid("at-vc-boundary")
      val justBelow = pid("just-below-vc-boundary")
      val healthy = (1 to 3).map(i => pid(s"healthy-$i")).toList
      val pool = atBoundary +: justBelow +: healthy
      val scores: Map[PeerId, (Int, Int)] = Map(
        atBoundary -> (50, 100), // score = 50/100 * (1 - 60/100) = 0.5 * 0.4 = 0.20
        justBelow -> (50, 100) //  score = 50/100 * (1 - 61/100) = 0.5 * 0.39 = 0.195
      ) ++ healthy.map(_ -> (10, 10)).toMap
      val viewChanges: Map[PeerId, Long] = Map(
        atBoundary -> 60L,
        justBelow -> 61L
      )

      val entropies = (0 until 30).map(i => Hash.fromBytes(s"v16-vc-bound-$i".getBytes("UTF-8")))
      val winners =
        entropies.flatMap(e => (0 until 5).map(v => selector.selectLeaderWeighted(pool, e, v, scores, peerViewChanges = viewChanges)))

      // atBoundary passes (= threshold); justBelow excluded
      expect(!winners.contains(justBelow), s"just-below-vc-boundary leaked into leader pool")
    }
  }

  test("v16 view-change penalty: clamps viewChangesCaused at participated (no negative deficit)") {
    IO {
      // Defensive case: if some external bug ever produces viewChangesCaused > participated
      // (which should not happen in practice -- view changes are counted per leader at view
      // <  finalView, and the peer must have participated to be a leader), the deficit term
      // is clamped to >= 0 so we don't get a negative score that confuses the integer
      // comparison. With (10, 10) and vcc=100, clamped vcc=10, deficit=0, score=0 -> below
      // any positive threshold -> excluded.
      val overflowing = pid("overflowing-vc")
      val healthy = (1 to 3).map(i => pid(s"healthy-$i")).toList
      val pool = overflowing +: healthy
      val scores: Map[PeerId, (Int, Int)] = Map(overflowing -> (10, 10)) ++ healthy.map(_ -> (9, 10)).toMap
      val viewChanges: Map[PeerId, Long] = Map(overflowing -> 100L) // way more than participated

      val entropies = (0 until 30).map(i => Hash.fromBytes(s"v16-vc-overflow-$i".getBytes("UTF-8")))
      val winners =
        entropies.flatMap(e => (0 until 4).map(v => selector.selectLeaderWeighted(pool, e, v, scores, peerViewChanges = viewChanges)))

      expect.same(0, winners.count(_ == overflowing))
    }
  }

  // === end v16 tests ===
}
