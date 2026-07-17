package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import weaver.SimpleIOSuite

/** Guards the recovery-escalation decision for abandoned keys (issue #1533).
  *
  * Architecture under test: rumor evidence supplies ONLY the fast path (a tip above the key
  * escalates directly). Every other rumor shape -- all tips below, a tip pinned AT the key by a
  * single pre-isolation declaration, or an empty map after a recovery wipe -- is ambiguous between
  * "this node is isolated" and "the whole cluster stalled", so the decision belongs to the HTTP
  * preflight: escalate iff a corroborated peer group reports the same committed snapshot identity
  * at/above the key. A genuine cluster-wide stall suppresses by ground truth (nobody committed it,
  * every probe answers false on every node), never by guessing from frozen rumor shapes.
  */
object AbandonmentEscalationSignalSuite extends SimpleIOSuite {

  private val key = 100L

  pureTest("fast path: any rumor tip above the key escalates with no probe") {
    val signal = AbandonmentTracker.escalationSignal(key, List(key - 3L, key + 2L))

    expect(signal.networkAdvanced, "one peer ahead is proof the cluster moved on") and
      expect(!signal.rumorStale) and
      expect(!signal.probeRequired(readyPeerCount = 5), "the fast path needs no external evidence") and
      expect(signal.decide(probeConfirmedAhead = false), "advanced escalates regardless of probe outcome")
  }

  pureTest("CASCADE CASE: every non-advanced shape suppresses when NO peer committed the key") {
    // A cluster-wide stall shows each node one of these three shapes, and the preflight finds no
    // committed snapshot at the key anywhere because nobody produced it. All must keep retrying;
    // escalating would put every node into WaitingForDownload with nothing downloadable.
    val allBelow = AbandonmentTracker.escalationSignal(key, List(key - 1L, key - 1L, key - 2L))
    val pinnedAtKey = AbandonmentTracker.escalationSignal(key, List(key, key - 1L))
    val emptyMap = AbandonmentTracker.escalationSignal(key, Nil: List[Long])

    expect(!allBelow.decide(probeConfirmedAhead = false), "all-below + unconfirmed probe keeps retrying") and
      expect(!pinnedAtKey.decide(probeConfirmedAhead = false), "at-key + unconfirmed probe keeps retrying") and
      expect(!emptyMap.decide(probeConfirmedAhead = false), "empty map + unconfirmed probe keeps retrying")
  }

  pureTest("isolation variant: all tips frozen strictly below + committed progress -> escalate") {
    val signal = AbandonmentTracker.escalationSignal(key, List(key - 1L, key - 2L))

    expect(!signal.networkAdvanced, "frozen registrations never show peers ahead: the #1533 gap") and
      expect(signal.rumorStale, "the classic frozen-mesh signature is labeled for the decision logs") and
      expect(signal.probeRequired(readyPeerCount = 3)) and
      expect(signal.decide(probeConfirmedAhead = true), "HTTP-confirmed committed progress escalates")
  }

  pureTest("isolation variant: tip pinned AT the key by a pre-isolation declaration -> probe decides") {
    // observePeerAtKey is monotone-max with no freshness: one declaration for key N received just
    // before losing gossip pins the entry at N forever. That is NOT evidence declarations still
    // flow, so the shape must probe -- and committed progress at N proves recovery has something
    // to fetch regardless of the cached rumor key.
    val signal = AbandonmentTracker.escalationSignal(key, List(key, key - 1L))

    expect(!signal.networkAdvanced) and
      expect(!signal.rumorStale, "at-key is not the all-below signature; it is still non-advanced") and
      expect(signal.probeRequired(readyPeerCount = 4), "every non-advanced shape probes when Ready peers exist") and
      expect(signal.decide(probeConfirmedAhead = true), "committed progress overrides the pinned cache") and
      expect(!signal.decide(probeConfirmedAhead = false), "no committed progress keeps retrying (kill4 stays safe)")
  }

  pureTest("isolation variant: empty map after a recovery wipe -> probe decides") {
    // clearAllPeerRegistrations empties peerCurrentKeys during recovery; a node isolated before
    // the next keyed rumor sees HTTP-Ready peers but an empty map forever. The probe must decide.
    val signal = AbandonmentTracker.escalationSignal(key, Nil: List[Long])

    expect(!signal.networkAdvanced) and
      expect(!signal.rumorStale, "an empty map is not the all-below signature") and
      expect(signal.probeRequired(readyPeerCount = 2), "empty map with Ready peers still probes") and
      expect(signal.decide(probeConfirmedAhead = true), "committed progress escalates the wiped-map isolate") and
      expect(!signal.decide(probeConfirmedAhead = false), "cold start stays suppressed: nobody committed the key")
  }

  pureTest("no Ready HTTP peers -> no probe, no escalation (nothing to ask, nothing to fetch)") {
    val signal = AbandonmentTracker.escalationSignal(key, List(key - 1L))

    expect(!signal.probeRequired(readyPeerCount = 0), "zero Ready peers means the probe is pointless") and
      expect(!signal.decide(probeConfirmedAhead = false), "and the unprobed decision suppresses")
  }

  pureTest("probe failure/timeout maps to not-confirmed and suppresses (degraded probes never trigger)") {
    val signal = AbandonmentTracker.escalationSignal(key, List(key - 1L))
    val failedProbe = AbandonmentTracker.PeersAheadProbe.none

    expect(!failedProbe.confirmedAhead, "PeersAheadProbe.none is the failed/skipped sentinel") and
      expect(!signal.decide(failedProbe.confirmedAhead), "a dead or timed-out probe keeps the node retrying")
  }

  pureTest("rumorStale classification labels only the strict all-below shape") {
    val allBelow = AbandonmentTracker.escalationSignal(key, List(key - 5L))
    val pinned = AbandonmentTracker.escalationSignal(key, List(key))
    val empty = AbandonmentTracker.escalationSignal(key, Nil: List[Long])
    val ahead = AbandonmentTracker.escalationSignal(key, List(key + 1L))

    expect(allBelow.rumorStale) and
      expect(!pinned.rumorStale) and
      expect(!empty.rumorStale) and
      expect(!ahead.rumorStale)
  }
}
