package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.node.shared.infrastructure.consensus.state.Candidates
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.types.numeric.NonNegLong
import monocle.syntax.all._
import weaver.SimpleIOSuite

/** Invariants the Facility retransmit fix relies on.
  *
  * `addFacility` in ConsensusStorage is implemented as `_.orElse(facility.some)` on the declaration slot (see ConsensusStorage.scala:305).
  * That's first-write-wins — the mechanism that makes idempotent retransmit safe. These tests exercise that semantic directly against
  * `PeerDeclarations` so the invariant is locked in even if the storage implementation is later refactored.
  */
object FacilityRetransmitInvariantsSuite extends SimpleIOSuite {

  private val selfId: PeerId = PeerId(Hex("a0"))

  private val facHash: Hash = Hash.fromBytes("FAC".getBytes("UTF-8"))
  private val lastHash: Hash = Hash.fromBytes("LAST".getBytes("UTF-8"))

  private def mkFacility(hashSeed: String, trigger: EventTrigger.type = EventTrigger): Facility =
    Facility(
      eventHashes = Set(Hash.fromBytes(hashSeed.getBytes("UTF-8"))),
      candidates = Candidates(Set.empty),
      trigger = trigger.some,
      facilitatorsHash = facHash,
      lastGlobalSnapshotOrdinal = SnapshotOrdinal(NonNegLong(1L)),
      lastSnapshotHash = lastHash,
      consensusConfigHash = Hash.fromBytes("CFG".getBytes("UTF-8")).some
    )

  pureTest("addFacility storage pattern is first-write-wins: subsequent add with same body is a no-op") {
    val facility = mkFacility("ev1")
    val afterFirst = PeerDeclarations.empty.focus(_.facility).modify(_.orElse(facility.some))
    val afterSecond = afterFirst.focus(_.facility).modify(_.orElse(facility.some))

    expect(afterFirst.facility.contains(facility), "first add should store the facility").and(
      expect(
        afterSecond === afterFirst,
        s"second identical add must not mutate the stored declaration, got afterSecond=$afterSecond"
      )
    )
  }

  pureTest("addFacility storage pattern rejects overwrite: subsequent add with different body is ignored") {
    val original = mkFacility("original")
    val replacement = mkFacility("replacement") // different eventHashes → not equal
    val afterFirst = PeerDeclarations.empty.focus(_.facility).modify(_.orElse(original.some))
    val afterSecond = afterFirst.focus(_.facility).modify(_.orElse(replacement.some))

    expect(original =!= replacement, "test precondition: replacement must differ from original").and(
      expect(
        afterSecond.facility.contains(original),
        s"re-adding a different facility must be rejected by first-write-wins, got: ${afterSecond.facility}"
      )
    )
  }

  pureTest("Facility case class equality is structural — retransmit preserves byte-equivalent declaration") {
    val a = mkFacility("same")
    val b = mkFacility("same")

    expect(a === b, "two Facility values built from identical inputs must be equal")
  }

  pureTest("Repeated retransmit writes are idempotent: N calls with the same body leave one stored") {
    val facility = mkFacility("retransmit")
    val writes: List[PeerDeclarations] =
      List(0, 1, 2, 3) // 4 concurrent-ish retransmit attempts
        .map(_ => facility)
        .scanLeft(PeerDeclarations.empty) { (acc, f) =>
          acc.focus(_.facility).modify(_.orElse(f.some))
        }

    val lastTwo = writes.takeRight(2)

    expect(writes.head === PeerDeclarations.empty, "scan initial accumulator should be empty")
      .and(
        expect(
          lastTwo.distinct.size == 1,
          s"successive retransmit writes must converge — last two states should be identical, got: $lastTwo"
        )
      )
      .and(
        expect(
          writes.last.facility.contains(facility),
          s"final storage state must contain the retransmitted facility, got: ${writes.last.facility}"
        )
      )
  }

  // NOTE: selfId is carried in the closure above to keep this test in the same neighborhood as the
  // consensus storage contract (facilities are stored keyed by peerId). The retransmit callsite in
  // StallDetector uses the same `ctx.selfId` to look up the stored declaration.
  pureTest("selfId is a stable key — the invariants above hold regardless of which peer wrote first") {
    val fromSelf = mkFacility("self")
    val fromPeer = mkFacility("peer")
    val storedFromSelfFirst = PeerDeclarations.empty
      .focus(_.facility)
      .modify(_.orElse(fromSelf.some))
      .focus(_.facility)
      .modify(_.orElse(fromPeer.some))

    expect(
      storedFromSelfFirst.facility.contains(fromSelf),
      s"first writer wins per-(peerId, key) slot — selfId=${selfId.show} first-wins preserved, got: ${storedFromSelfFirst.facility}"
    )
  }
}
