package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

/** Regression coverage for `StallDetector.selectEvictionTargets` — the per-emission target selection that needs to converge across honest
  * nodes when missing peers exceed the per-voter cap. Codex review finding #2: prior code used `Set.take(N)` which iterates in unspecified
  * order, so two honest nodes could vote for different subsets and cert quorum would starve.
  */
object StallDetectorEvictionTargetSelectionSuite extends FunSuite {

  private def pid(hex: String): PeerId = PeerId(Hex(hex))

  // Deliberately construct committee members with hex-sortable IDs so the expected ordering is
  // easy to read in the tests below.
  private val p01: PeerId = pid("01" * 64)
  private val p02: PeerId = pid("02" * 64)
  private val p03: PeerId = pid("03" * 64)
  private val p04: PeerId = pid("04" * 64)
  private val p05: PeerId = pid("05" * 64)
  private val p06: PeerId = pid("06" * 64)
  private val p07: PeerId = pid("07" * 64)
  private val p08: PeerId = pid("08" * 64)
  private val p09: PeerId = pid("09" * 64)
  private val p10: PeerId = pid("0a" * 64)

  private val committee10: Set[PeerId] = Set(p01, p02, p03, p04, p05, p06, p07, p08, p09, p10)

  // BFT n=3f+1 with quorum 2f+1: for n=10 the closest valid is f=3, quorum=7 → cap = 10-7 = 3.
  // For n=9 (committee9 below) f=2, quorum=7 → cap = 9-7 = 2.
  private val quorum10: Int = 7

  private val committee9: Set[PeerId] = committee10 - p10
  private val quorum9: Int = 7

  test("returns empty when per-voter cap is already exhausted") {
    // committee=10, minQuorum=7 → cap = 3. alreadyVoted = 3 → remainingSlots = 0.
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(p05, p06, p07, p08),
      committee = committee10,
      alreadyVotedBySelf = Set(p02, p03, p04),
      minQuorum = quorum10
    )
    expect.same(List.empty[PeerId], result)
  }

  test("filters out peers not in committee") {
    val outsider = pid("ff" * 64)
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(outsider, p05),
      committee = committee10,
      alreadyVotedBySelf = Set.empty,
      minQuorum = quorum10
    )
    expect.same(List(p05), result)
  }

  test("filters out peers already voted by self") {
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(p05, p06, p07),
      committee = committee10,
      alreadyVotedBySelf = Set(p06),
      minQuorum = quorum10
    )
    // p06 excluded via already-voted filter; p05 and p07 remain (cap=3, 1 already used → slots=2), sorted.
    expect.same(List(p05, p07), result)
  }

  test("applies per-voter cap when unresponsiveMissing exceeds remaining slots") {
    // committee=10, minQuorum=7 → cap = 3. None voted yet → remainingSlots = 3.
    // 5 missing peers present; only 3 selected by canonical hex order.
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(p05, p06, p07, p08, p09),
      committee = committee10,
      alreadyVotedBySelf = Set.empty,
      minQuorum = quorum10
    )
    expect.same(List(p05, p06, p07), result)
  }

  test("deterministic ordering under Set-input permutation (codex review #2)") {
    // The critical property: two honest nodes observing the same logical missing-peers set
    // must pick the SAME subset under the cap, even if their in-memory Set iteration order
    // differs. The function must sort before applying `.take`.
    val missing = Set(p05, p06, p07, p08, p09, p10)
    val permutation1 = Set(p10, p09, p08, p07, p06, p05) // reverse insertion
    val permutation2 = Set(p07, p10, p05, p08, p09, p06) // scrambled
    val r0 = StallDetector.selectEvictionTargets(p01, missing, committee10, Set.empty, quorum10)
    val r1 = StallDetector.selectEvictionTargets(p01, permutation1, committee10, Set.empty, quorum10)
    val r2 = StallDetector.selectEvictionTargets(p01, permutation2, committee10, Set.empty, quorum10)
    expect
      .same(r0, r1)
      .and(expect.same(r0, r2))
      .and(
        expect(r0.size === (committee10.size - quorum10), s"expected cap=${committee10.size - quorum10} selections, got $r0")
      )
  }

  test("sorted output: first-K targets by hex identity") {
    val missing = Set(p10, p05, p08, p03, p07, p09)
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = missing,
      committee = committee10,
      alreadyVotedBySelf = Set.empty,
      minQuorum = quorum10
    )
    // Sorted by hex: p03, p05, p07, p08, p09, p10 → cap=3 → take first 3
    expect.same(List(p03, p05, p07), result)
  }

  test("empty missing set returns empty") {
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set.empty,
      committee = committee10,
      alreadyVotedBySelf = Set.empty,
      minQuorum = quorum10
    )
    expect.same(List.empty[PeerId], result)
  }

  test("self-exclusion: never emits a vote targeting selfId (E2E regression)") {
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(p01, p05, p06),
      committee = committee10,
      alreadyVotedBySelf = Set.empty,
      minQuorum = quorum10
    )
    expect(!result.contains(p01), s"result must never include selfId, got: $result").and(
      expect.same(List(p05, p06), result)
    )
  }

  test("self-exclusion: selfId-only in unresponsiveMissing returns empty") {
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(p01),
      committee = committee10,
      alreadyVotedBySelf = Set.empty,
      minQuorum = quorum10
    )
    expect.same(List.empty[PeerId], result)
  }

  test("multi-node agreement: different alreadyVoted sets still converge on prefix") {
    // Honest node A has voted for {p05}, node B has voted for {} — but neither is near cap.
    // Each must pick a sorted prefix; because p05 is already-voted-by-A, A's remaining
    // candidates differ — that's fine AS LONG AS both agree on the sorted ORDER.
    // committee=10, minQuorum=7 → cap=3.
    val missing = Set(p05, p06, p07, p08, p09, p10)
    val nodeAResult = StallDetector.selectEvictionTargets(p01, missing, committee10, Set(p05), quorum10)
    val nodeBResult = StallDetector.selectEvictionTargets(p01, missing, committee10, Set.empty, quorum10)
    // Node A: cap=3, alreadyVoted={p05} → remainingSlots=2. Excluded p05 → {p06, p07}.
    expect
      .same(List(p06, p07), nodeAResult)
      .and(
        // Node B: cap=3, no prior votes → first 3 in canonical order = {p05, p06, p07}.
        expect.same(List(p05, p06, p07), nodeBResult)
      )
      .and(
        // Overlap: {p06, p07} — peers BOTH nodes vote on, guaranteeing quorum-forming progress.
        expect(nodeAResult.toSet.intersect(nodeBResult.toSet).size === 2, "overlap insufficient for quorum progress")
      )
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Regression suite: quorum-aware cap prevents over-eviction
  // ─────────────────────────────────────────────────────────────────────────

  test("quorum-aware cap: 9-committee with 7-quorum caps at 2 (BFT f=2)") {
    // The testnet deadlock pattern: post-restart 9-committee with 5+ silent peers.
    // Pre-fix cap was ceil(9/3)=3, so honest voters agreed on 3 canonical-prefix
    // targets. Cert-finalization shrunk committee 9→6, but minQuorum on a 6-committee
    // (0.78 fraction) = 5 — and with 5+ silent peers, only 4 honest remained, breaking
    // quorum. Quorum-aware cap = 9-7 = 2, leaving committee=7 which can still form 7-quorum.
    val missing = Set(p03, p04, p05, p06, p07, p08, p09)
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = missing,
      committee = committee9,
      alreadyVotedBySelf = Set.empty,
      minQuorum = quorum9
    )
    expect
      .same(List(p03, p04), result)
      .and(
        expect(result.size <= committee9.size - quorum9, s"cap must respect committee_size - minQuorum bound, got $result")
      )
  }

  test("quorum-aware cap: cap becomes 0 when committee is exactly at minQuorum") {
    // 7-committee with 7-quorum: any eviction breaks quorum, so cap=0.
    val committee7 = Set(p01, p02, p03, p04, p05, p06, p07)
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(p05, p06),
      committee = committee7,
      alreadyVotedBySelf = Set.empty,
      minQuorum = 7
    )
    expect.same(List.empty[PeerId], result)
  }

  test("quorum-aware cap: aggregate evictions across all honest voters bounded by cap") {
    // Across a round, ALL honest voters together select the same canonical-prefix subset
    // because of the deterministic sort. Even if 4 honest voters each call the function
    // independently, the union of their selections has size <= cap. This test simulates
    // that scenario: voters with empty alreadyVoted converge on the same cap-sized prefix.
    val missing = Set(p03, p04, p05, p06, p07, p08, p09)
    val voterA = StallDetector.selectEvictionTargets(p01, missing, committee9, Set.empty, quorum9)
    val voterB = StallDetector.selectEvictionTargets(p02, missing, committee9, Set.empty, quorum9)
    // Different selfId, but neither is in `missing` so the result depends only on the
    // canonical sort of (missing - {selfId}). Both voters agree on {p03, p04}.
    expect
      .same(voterA, voterB)
      .and(
        expect(voterA.toSet.union(voterB.toSet).size <= committee9.size - quorum9, "aggregate selection must not exceed cap")
      )
  }

  test("quorum-aware cap: large committee preserves f-fault tolerance") {
    // BFT n=16, f=5, quorum=11 → cap = 16-11 = 5.
    val pids = (1 to 16).map(i => pid(f"$i%02x" * 64)).toSet
    val committee16 = pids
    val missing = pids.toList.sortBy(_.value.value).take(8).toSet
    val self = pids.toList.sortBy(_.value.value).head
    val result = StallDetector.selectEvictionTargets(
      selfId = self,
      unresponsiveMissing = missing,
      committee = committee16,
      alreadyVotedBySelf = Set.empty,
      minQuorum = 11
    )
    expect(result.size === 5, s"expected cap=5 for 16-committee/11-quorum, got ${result.size}").and(
      expect(result.forall(committee16.contains), "all targets must be in committee")
    )
  }
}
