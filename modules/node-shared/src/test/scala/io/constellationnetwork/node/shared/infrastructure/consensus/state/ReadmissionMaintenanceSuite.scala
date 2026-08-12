package io.constellationnetwork.node.shared.infrastructure.consensus.state

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import weaver.FunSuite

/** Verifies the v12 sticky-probation invariant: a peer's readmissionCountdown ENTRY persists across rounds even after the counter clamps to
  * 0. The only path that removes the entry is an accepted `AdmissionCertificate` (passed in here as `admittedThisRound`).
  *
  * Pre-v12 had `.filter(_._2 > 0)` after the decrement, which dropped the entry on the round it reached 0 — making the AdmissionCertificate
  * path semantically optional and the StallDetector emission gate (probation ∩ atTip ∩ streak) starve for candidates.
  */
object ReadmissionMaintenanceSuite extends FunSuite {

  private def peer(tag: String): PeerId =
    PeerId(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString.padTo(64, '0')))

  private val pA = peer("a")
  private val pB = peer("b")
  private val pC = peer("c")

  test("decrement clamps at 0 — entry persists at 0 indefinitely (v12 sticky behavior)") {
    var current: SortedMap[PeerId, Int] = SortedMap(pA -> 2)
    // Round 1 — countdown 2 → 1
    current = ReadmissionMaintenance.step(current, Set.empty, Set.empty, 5)
    expect(current.get(pA).contains(1), s"after round 1 expected pA=1, got ${current.get(pA)}").and
      // Round 2 — countdown 1 → 0
      {
        current = ReadmissionMaintenance.step(current, Set.empty, Set.empty, 5)
        expect(current.get(pA).contains(0), s"after round 2 expected pA=0, got ${current.get(pA)}")
      }.and
      // Round 3 — pre-v12 would have dropped this; v12 keeps it pinned at 0
      {
        current = ReadmissionMaintenance.step(current, Set.empty, Set.empty, 5)
        expect(current.contains(pA), s"v12 invariant: pA must still be in probation map (key present)").and(
          expect(current.get(pA).contains(0), s"clamped at 0, got ${current.get(pA)}")
        )
      }.and
      // Round 4 — still stuck at 0 with no ACS
      {
        current = ReadmissionMaintenance.step(current, Set.empty, Set.empty, 5)
        expect(current.contains(pA), "still in probation 4 rounds after countdown started")
      }
  }

  test("only an AdmissionCertificate (admittedThisRound) clears a probation entry") {
    val initial = SortedMap(pA -> 0, pB -> 1, pC -> 3)
    // Admit pA (countdown was already at 0); pB and pC remain
    val afterAdmit = ReadmissionMaintenance.step(initial, Set.empty, Set(pA), 5)
    expect(!afterAdmit.contains(pA), "pA cleared by ACS").and(
      expect(afterAdmit.get(pB).contains(0), "pB decremented 1 → 0 and persists").and(
        expect(afterAdmit.get(pC).contains(2), "pC decremented 3 → 2")
      )
    )
  }

  test("probation membership includes sticky zero-countdown entries") {
    val countdown = SortedMap(pA -> 0, pB -> 1)

    expect.same(Set(pA, pB), ReadmissionMaintenance.probationPeers(countdown))
  }

  test("operational persistence distinguishes sticky zero from absent membership") {
    val countdown = SortedMap(pA -> 0, pB -> 3)

    expect.same(1, ReadmissionMaintenance.persistenceValue(countdown, pA)) &&
    expect.same(3, ReadmissionMaintenance.persistenceValue(countdown, pB)) &&
    expect.same(0, ReadmissionMaintenance.persistenceValue(countdown, pC))
  }

  test("justUnpenalized seeds new entries at probationRounds") {
    val initial = SortedMap(pA -> 5)
    val afterSeed = ReadmissionMaintenance.step(initial, Set(pB), Set.empty, 8)
    expect(afterSeed.get(pA).contains(4), "pA decremented 5 → 4").and(
      expect(afterSeed.get(pB).contains(8), "pB seeded at probationRounds=8")
    )
  }

  test("justUnpenalized does NOT clobber an existing entry") {
    val initial = SortedMap(pA -> 2)
    val afterSeed = ReadmissionMaintenance.step(initial, Set(pA), Set.empty, 10)
    expect(afterSeed.get(pA).contains(1), s"existing pA decremented (not re-seeded), got ${afterSeed.get(pA)}")
  }

  test("admittedThisRound wins over justUnpenalized for the same peer (admit step applies last)") {
    val initial = SortedMap.empty[PeerId, Int]
    // pA is both newly-unpenalized AND admitted in the same round (defensive case the comment
    // calls out as 'shouldn't happen but defended against')
    val result = ReadmissionMaintenance.step(initial, Set(pA), Set(pA), 5)
    expect(!result.contains(pA), "admit step strips the seeded entry — pA should not be on probation")
  }

  test("probationRounds <= 0 disables seeding (decrement still applies)") {
    val initial = SortedMap(pA -> 3)
    val afterStep = ReadmissionMaintenance.step(initial, Set(pB), Set.empty, 0)
    expect(afterStep.get(pA).contains(2), "pA decremented even with seeding disabled").and(
      expect(!afterStep.contains(pB), "pB NOT seeded because probationRounds=0")
    )
  }

  test("empty input returns empty output (no-op corner)") {
    expect.same(SortedMap.empty[PeerId, Int], ReadmissionMaintenance.step(SortedMap.empty, Set.empty, Set.empty, 5))
  }

  test("multi-peer round: independent decrement, no cross-peer interference") {
    val initial = SortedMap(pA -> 5, pB -> 1, pC -> 0)
    val afterStep = ReadmissionMaintenance.step(initial, Set.empty, Set.empty, 10)
    expect.same(SortedMap(pA -> 4, pB -> 0, pC -> 0), afterStep)
  }
}
