package io.constellationnetwork.node.shared.infrastructure.gossip

import io.constellationnetwork.schema.gossip.Counter

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import weaver.SimpleIOSuite

/** Guards the unclosable-gap bound that lets a returning peer's rumor chain restart.
  *
  * Background: `addPeerRumorIfConsecutive` accepts a peer rumor only when its counter is exactly `head + 1`. A peer that is unreachable for
  * longer than its own rumor buffer therefore comes back permanently muted -- every rumor it sends is `CounterTooHigh` and silently
  * dropped, including its `NodeState` broadcasts and all of its consensus declarations. Because committees derive from `peerHistory` rather
  * than from gossip reachability, such a peer can still be seated and elected leader, at which point no follower can hear it and the chain
  * wedges. Observed on a 5-node rig: ordinal 41 held for 900s with all nodes Ready and at tip.
  *
  * The bound below is what makes the restart safe to do automatically: it fires only when the missing range provably cannot be served by
  * anyone, so it can never pre-empt the ordinary `peerRound` inquiry repair.
  */
object RumorChainGapSuite extends SimpleIOSuite {

  private def counter(n: Long): Counter = Counter(PosLong.unsafeFrom(n))

  private val capacity: PosLong = PosLong.unsafeFrom(50L)

  pureTest("a consecutive counter is never treated as an unclosable gap") {
    expect(
      !RumorStorage.isGapUnclosable(counter(100L), counter(101L), capacity),
      "head+1 is the normal accept path and must never restart the chain"
    )
  }

  pureTest("a gap smaller than the retention window waits for the inquiry to repair it") {
    expect(
      !RumorStorage.isGapUnclosable(counter(100L), counter(120L), capacity),
      "a 20-rumor gap is still held by the origin, so peerRound can fetch counter 101"
    )
  }

  pureTest("a gap of exactly the retention window is still closable") {
    expect(
      !RumorStorage.isGapUnclosable(counter(100L), counter(150L), capacity),
      "the origin retains its last 50 rumors, so counter 101 is the oldest it can still serve"
    )
  }

  pureTest("a gap one beyond the retention window is unclosable") {
    expect(
      RumorStorage.isGapUnclosable(counter(100L), counter(151L), capacity),
      "counter 101 has been evicted everywhere, so waiting for it would mute the peer forever"
    )
  }

  pureTest("a long isolation produces an unclosable gap") {
    expect(
      RumorStorage.isGapUnclosable(counter(100L), counter(5000L), capacity),
      "a peer isolated for hundreds of rounds must be able to restart its chain"
    )
  }

  pureTest("a counter at or below the head is never an unclosable gap") {
    expect(
      !RumorStorage.isGapUnclosable(counter(100L), counter(100L), capacity),
      "an equal counter is a duplicate, handled as CounterTooLow"
    ).and(
      expect(
        !RumorStorage.isGapUnclosable(counter(100L), counter(40L), capacity),
        "a counter below the head must not be mistaken for a forward gap"
      )
    )
  }

  pureTest("the bound scales with the configured capacity") {
    val small: PosLong = PosLong.unsafeFrom(2L)

    expect(
      !RumorStorage.isGapUnclosable(counter(10L), counter(12L), small),
      "a gap of exactly the (small) capacity is still closable"
    ).and(
      expect(
        RumorStorage.isGapUnclosable(counter(10L), counter(13L), small),
        "one beyond the (small) capacity is unclosable"
      )
    )
  }
}
