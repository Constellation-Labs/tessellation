package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

/** Regression coverage for `StallDetector.selectEvictionTargets` — the per-emission target
  * selection that needs to converge across honest nodes when missing peers exceed the per-voter
  * cap. Codex review finding #2: prior code used `Set.take(N)` which iterates in unspecified
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

  test("returns empty when per-voter cap is already exhausted") {
    // cap = ceil(10/3) = 4. alreadyVoted = 4 → remainingSlots = 0.
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(p05, p06, p07, p08),
      committee = committee10,
      alreadyVotedBySelf = Set(p01, p02, p03, p04)
    )
    expect.same(List.empty[PeerId], result)
  }

  test("filters out peers not in committee") {
    val outsider = pid("ff" * 64)
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(outsider, p05),
      committee = committee10,
      alreadyVotedBySelf = Set.empty
    )
    expect.same(List(p05), result)
  }

  test("filters out peers already voted by self") {
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(p05, p06, p07),
      committee = committee10,
      alreadyVotedBySelf = Set(p06)
    )
    // p06 excluded via already-voted filter; p05 and p07 remain, sorted.
    expect.same(List(p05, p07), result)
  }

  test("applies per-voter cap when unresponsiveMissing exceeds remaining slots") {
    // cap = ceil(10/3) = 4. None voted yet → remainingSlots = 4.
    // 5 missing peers present; only 4 selected.
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(p05, p06, p07, p08, p09),
      committee = committee10,
      alreadyVotedBySelf = Set.empty
    )
    expect.same(List(p05, p06, p07, p08), result)
  }

  test("deterministic ordering under Set-input permutation (codex review #2)") {
    // The critical property: two honest nodes observing the same logical missing-peers set
    // must pick the SAME subset under the cap, even if their in-memory Set iteration order
    // differs. The function must sort before applying `.take`.
    val missing = Set(p05, p06, p07, p08, p09, p10)
    // Scala standard-library Sets iterate in a predictable order for small hash sets, but
    // this test encodes the contract: insertion-order variants must yield identical outputs.
    val permutation1 = Set(p10, p09, p08, p07, p06, p05) // reverse insertion
    val permutation2 = Set(p07, p10, p05, p08, p09, p06) // scrambled
    val r0 = StallDetector.selectEvictionTargets(p01, missing, committee10, Set.empty)
    val r1 = StallDetector.selectEvictionTargets(p01, permutation1, committee10, Set.empty)
    val r2 = StallDetector.selectEvictionTargets(p01, permutation2, committee10, Set.empty)
    expect.same(r0, r1).and(expect.same(r0, r2)).and(
      expect(r0.size === math.ceil(committee10.size.toDouble / 3.0).toInt, s"expected cap=4 selections, got $r0")
    )
  }

  test("sorted output: first-K targets by hex identity") {
    // Deterministic order = canonical PeerId hex. Verify result matches expected prefix.
    val missing = Set(p10, p05, p08, p03, p07, p09)
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = missing,
      committee = committee10,
      alreadyVotedBySelf = Set.empty
    )
    // Sorted by hex: p03, p05, p07, p08, p09, p10 → cap=4 → take first 4
    expect.same(List(p03, p05, p07, p08), result)
  }

  test("empty missing set returns empty") {
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set.empty,
      committee = committee10,
      alreadyVotedBySelf = Set.empty
    )
    expect.same(List.empty[PeerId], result)
  }

  test("self-exclusion: never emits a vote targeting selfId (E2E regression)") {
    // Regression for the 2026-04-21 E2E failure in Phase 1 sync. `clusterStorage.getResponsivePeers`
    // does not list selfId (clusterStorage tracks other peers, not this node), so during a phase
    // transition when self has not yet posted a declaration, self ends up in both `missingPeers`
    // and (consequently) `unresponsiveMissing`. Prior to this guard, the node would emit
    // `Signed[EvictionVote(targetPeer=selfId)]` — a self-eviction vote, which is nonsensical.
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(p01, p05, p06),
      committee = committee10,
      alreadyVotedBySelf = Set.empty
    )
    expect(!result.contains(p01), s"result must never include selfId, got: $result").and(
      expect.same(List(p05, p06), result)
    )
  }

  test("self-exclusion: selfId-only in unresponsiveMissing returns empty") {
    // Edge case of the self-exclusion regression. If the ONLY candidate is selfId, we emit
    // nothing. Previously this would emit a single self-eviction vote.
    val result = StallDetector.selectEvictionTargets(
      selfId = p01,
      unresponsiveMissing = Set(p01),
      committee = committee10,
      alreadyVotedBySelf = Set.empty
    )
    expect.same(List.empty[PeerId], result)
  }

  test("multi-node agreement: different alreadyVoted sets still converge on prefix") {
    // Honest node A has voted for {p05}, node B has voted for {} — but neither is near cap.
    // Missing peers exceed either's remaining slots. Each must pick a sorted prefix;
    // because p05 is already-voted-by-A, A's remaining candidates differ — that's fine
    // AS LONG AS both agree on the sorted ORDER, so that overlap between selections is
    // maximized. We check that node A selects {p06, p07, p08, p09} (skipping p05 it already
    // voted on) and node B selects {p05, p06, p07, p08} — the first 4 by hex-order.
    val missing = Set(p05, p06, p07, p08, p09, p10)
    val nodeAResult = StallDetector.selectEvictionTargets(p01, missing, committee10, Set(p05))
    val nodeBResult = StallDetector.selectEvictionTargets(p01, missing, committee10, Set.empty)
    // Node A: remainingSlots = 4 - 1 = 3 (already voted for 1). Excluded p05.
    expect.same(List(p06, p07, p08), nodeAResult).and(
      expect.same(List(p05, p06, p07, p08), nodeBResult)
    ).and(
      // Overlap: {p06, p07, p08} — three peers that BOTH nodes vote on, guaranteeing
      // quorum-forming progress even with per-voter caps.
      expect((nodeAResult.toSet intersect nodeBResult.toSet).size === 3, "overlap insufficient for quorum progress")
    )
  }
}
