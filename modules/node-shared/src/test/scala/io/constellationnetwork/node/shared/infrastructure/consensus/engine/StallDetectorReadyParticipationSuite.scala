package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

/** Alpha.98 regression coverage for `StallDetector.computeReadyParticipationStatus` -- the local-only round-start participation feasibility
  * check that fires when the Core committee includes peers we cannot locally observe as Ready, and excluding them would drop us below
  * quorum. The check is a LOCAL-ABANDON-ONLY guard; it does not mutate the committee, facilitator hash, or quorum derivation (verified by
  * inspection -- this suite locks the arithmetic of the helper used by the inline path).
  *
  * Codex v2 review (testnet 2026-05-22) locked down two specific bugs in the v1 version of this check:
  *   1. self was NOT counted as Ready, causing 2-Core rounds to falsely fire as infeasible because self + 1 peer = activeReady = 1 <
  *      quorum=2. Fixed by adding `selfId` to the Ready set before the predicate runs. 2. The exclusion predicate originally required peer
  *      to be "not Ready AND tip-behind". A WFR-but-caught-up peer (the .79 WFR-promotion-starvation case) would pass that filter (not
  *      Ready, but at-key) and be counted as active even though it cannot sign or lead. Fixed by classifying ALL non-Ready Core peers as
  *      non-participatory; the tip-behind subset is now a diagnostic dimension only.
  */
object StallDetectorReadyParticipationSuite extends FunSuite {

  private val Supermajority: Double = 2.0 / 3.0

  private def pid(hex: String): PeerId = PeerId(Hex(hex))

  // Hex-sortable IDs for readable expectations.
  private val self: PeerId = pid("01" * 64)
  private val ready: PeerId = pid("02" * 64)
  private val wfr: PeerId = pid("03" * 64)
  private val another: PeerId = pid("04" * 64)

  private def neverKnown(_id: PeerId): Boolean = false
  private def alwaysKnown(_id: PeerId): Boolean = true
  private def alwaysAtOrAfter(_id: PeerId): Boolean = true
  private def neverAtOrAfter(_id: PeerId): Boolean = false

  // ---------------------------------------------------------------------------
  // Codex invariant 1: self in Core is counted active
  // ---------------------------------------------------------------------------

  test("self in Core counts as active even if absent from external readyPeerIds (clusterStorage excludes self by design)") {
    // 2-Core round: self + one external Ready peer. clusterStorage.getResponsivePeers
    // does NOT include self (only foreign peers in its responsive map), so the raw
    // readyPeerIds passed in is {ready}. The helper MUST add self before filtering or
    // it will classify self as not-Ready and false-fire infeasible.
    val status = StallDetector.computeReadyParticipationStatus(
      coreFacilitators = Set(self, ready),
      readyPeerIds = Set(ready),
      selfId = self,
      peerCurrentKeysContains = alwaysKnown,
      peerCurrentKeyAtOrAfter = alwaysAtOrAfter,
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(2, status.coreSize)
      .and(expect.same(2, status.activeReady))
      .and(expect.same(2, status.coreQuorum))
      .and(expect.same(0, status.notReadyCore))
      .and(expect.same(0, status.behindNonReady))
      .and(expect(!status.infeasible, "self + 1 Ready peer should NOT be flagged infeasible in a 2-Core round"))
  }

  // ---------------------------------------------------------------------------
  // Codex invariant 2: non-Ready Core peer causes local abandon when quorum would be infeasible
  // ---------------------------------------------------------------------------

  test("non-Ready Core peer in a 3-Core round drops active=2, quorum stays 2, NOT infeasible") {
    // 3-Core round, one Core peer in WFR. quorum=ceil(3*2/3)=2, activeReady=2 (self + ready).
    // 2 == 2, NOT infeasible.
    val status = StallDetector.computeReadyParticipationStatus(
      coreFacilitators = Set(self, ready, wfr),
      readyPeerIds = Set(ready),
      selfId = self,
      peerCurrentKeysContains = alwaysKnown,
      peerCurrentKeyAtOrAfter = alwaysAtOrAfter,
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(3, status.coreSize)
      .and(expect.same(2, status.activeReady))
      .and(expect.same(2, status.coreQuorum))
      .and(expect.same(1, status.notReadyCore))
      .and(expect(!status.infeasible, "3-Core with 1 non-Ready peer (self + 1 Ready) hits quorum exactly, not infeasible"))
  }

  test("two non-Ready Core peers in a 3-Core round drop active=1 below quorum=2, INFEASIBLE fires") {
    // 3-Core round, two Core peers in WFR. activeReady=1 (just self), coreQuorum=2.
    // 1 < 2 -> infeasible.
    val status = StallDetector.computeReadyParticipationStatus(
      coreFacilitators = Set(self, wfr, another),
      readyPeerIds = Set.empty,
      selfId = self,
      peerCurrentKeysContains = alwaysKnown,
      peerCurrentKeyAtOrAfter = alwaysAtOrAfter,
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(3, status.coreSize)
      .and(expect.same(1, status.activeReady))
      .and(expect.same(2, status.coreQuorum))
      .and(expect.same(2, status.notReadyCore))
      .and(expect(status.infeasible, "3-Core with 2 non-Ready peers leaves only self active; below quorum=2"))
  }

  test("in a 2-Core round where the non-self peer is non-Ready, active=1 < quorum=2 -> INFEASIBLE") {
    val status = StallDetector.computeReadyParticipationStatus(
      coreFacilitators = Set(self, wfr),
      readyPeerIds = Set.empty,
      selfId = self,
      peerCurrentKeysContains = alwaysKnown,
      peerCurrentKeyAtOrAfter = alwaysAtOrAfter,
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(2, status.coreSize)
      .and(expect.same(1, status.activeReady))
      .and(expect.same(2, status.coreQuorum))
      .and(expect.same(1, status.notReadyCore))
      .and(expect(status.infeasible, "2-Core with 1 non-Ready peer leaves only self active; below quorum=2"))
  }

  // ---------------------------------------------------------------------------
  // Codex invariant 3: caught-up WFR peer is still treated as non-participatory
  // ---------------------------------------------------------------------------

  test("caught-up WFR peer (in peerCurrentKeys, at-or-after lastOutcomeKey) is STILL classified as non-Ready and excluded") {
    // The .79 testnet 2026-05-22 case: WFR after recovery, tip caught up to cluster, but
    // unable to sign/lead. The v1 check (exclude only if !Ready AND behind) would have
    // missed this. v2 excludes ALL non-Ready peers; behindNonReady is just a diagnostic.
    val status = StallDetector.computeReadyParticipationStatus(
      coreFacilitators = Set(self, ready, wfr),
      readyPeerIds = Set(ready),
      selfId = self,
      peerCurrentKeysContains = alwaysKnown,
      peerCurrentKeyAtOrAfter = alwaysAtOrAfter, // wfr appears caught-up
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(1, status.notReadyCore)
      .and(expect.same(0, status.behindNonReady))
      .and(expect.same(2, status.activeReady))
      .and(expect(!status.infeasible, "3-Core with 1 caught-up WFR peer still has 2 active (self + ready) >= quorum=2"))
  }

  test("WFR peer that is also behind shows up in behindNonReady diagnostic") {
    val status = StallDetector.computeReadyParticipationStatus(
      coreFacilitators = Set(self, ready, wfr),
      readyPeerIds = Set(ready),
      selfId = self,
      peerCurrentKeysContains = alwaysKnown,
      peerCurrentKeyAtOrAfter = neverAtOrAfter, // wfr is behind
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(1, status.notReadyCore)
      .and(expect.same(1, status.behindNonReady))
  }

  test("WFR peer that is absent from peerCurrentKeys also shows up in behindNonReady diagnostic") {
    val status = StallDetector.computeReadyParticipationStatus(
      coreFacilitators = Set(self, ready, wfr),
      readyPeerIds = Set(ready),
      selfId = self,
      peerCurrentKeysContains = neverKnown, // no observation of wfr's tip
      peerCurrentKeyAtOrAfter = neverAtOrAfter,
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(1, status.notReadyCore)
      .and(expect.same(1, status.behindNonReady))
  }

  // ---------------------------------------------------------------------------
  // Solo-core guard: coreSize < 2 NEVER fires infeasible (single-node testnet bootstrap)
  // ---------------------------------------------------------------------------

  test("solo-Core (coreSize=1) never fires infeasible regardless of state") {
    // Even if self is somehow not in readyPeerIds, a 1-Core round is a degenerate case
    // (single-node testnet bootstrap, solo block-production) and the existing solo-core
    // bypass elsewhere in the FSM handles it. We guard against firing infeasible at
    // coreSize < 2 to avoid interfering.
    val status = StallDetector.computeReadyParticipationStatus(
      coreFacilitators = Set(self),
      readyPeerIds = Set.empty,
      selfId = self,
      peerCurrentKeysContains = neverKnown,
      peerCurrentKeyAtOrAfter = neverAtOrAfter,
      quorumThresholdFraction = Supermajority
    )
    expect
      .same(1, status.coreSize)
      .and(expect(!status.infeasible, "coreSize=1 must never be flagged infeasible; solo-core is the FSM's responsibility"))
  }

  // ---------------------------------------------------------------------------
  // Codex invariant 4: this suite tests the pure helper; the committee/hash invariance
  // is enforced at the call site (StallDetector inlines this helper and does NOT
  // mutate state.coreFacilitators, state.roundStartFacilitators, or anything
  // facilitator-hash-related). The behavioural contract of `computeReadyParticipationStatus`
  // (above) plus the lack of any such mutation in `StallDetector.runMonitorCycle` is
  // the proof.
  // ---------------------------------------------------------------------------
}
