package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import io.constellationnetwork.node.shared.infrastructure.consensus.engine.AbandonmentTracker.{PeersAheadProbe, ProbeOutcome, SuppressedBy}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.PeersCommittedAheadProbe.PeerResult
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.snapshot.SnapshotMetadata
import io.constellationnetwork.security.hash.Hash

import weaver.SimpleIOSuite

/** D2 precedence table: probe results -> `suppressedBy`. Telemetry only; every row also pins that the decision (`decide`) is unchanged by
  * the classification.
  */
object EscalationSuppressedBySuite extends SimpleIOSuite {

  private val key = SnapshotOrdinal.unsafeApply(100L)
  private def meta(n: Long, hash: String = "h"): PeerResult =
    PeerResult.Responded(SnapshotMetadata(SnapshotOrdinal.unsafeApply(n), Hash(hash), Hash("p")))
  private def probeOf(results: List[PeerResult], sampled: Int): PeersAheadProbe =
    PeersCommittedAheadProbe.summarize(results, sampled, key, minCorroborators = 2)

  private val notAdvanced = AbandonmentTracker.escalationSignal(100L, List(99L))
  private val advanced = AbandonmentTracker.escalationSignal(100L, List(101L))

  private def classify(
    thresholdMet: Boolean,
    readyPeers: Int,
    signal: AbandonmentTracker.EscalationSignal,
    probe: PeersAheadProbe,
    scheduling: Option[SuppressedBy] = None
  ) =
    AbandonmentTracker.suppressedBy(thresholdMet, readyPeers, signal, probe, escalated = signal.decide(probe.confirmedAhead), scheduling)

  pureTest("precedence table") {
    val table: List[(String, SuppressedBy, SuppressedBy)] = List(
      (
        "below threshold: nothing gathered",
        classify(thresholdMet = false, readyPeers = 5, notAdvanced, PeersAheadProbe.none),
        SuppressedBy.Threshold
      ),
      (
        "threshold met, no Ready HTTP peer to ask",
        classify(thresholdMet = true, readyPeers = 0, notAdvanced, PeersAheadProbe.none),
        SuppressedBy.NoCandidates
      ),
      (
        "overall probe timeout",
        classify(thresholdMet = true, readyPeers = 3, notAdvanced, PeersAheadProbe.timedOut),
        SuppressedBy.ProbeTimeout
      ),
      (
        "probe failure",
        classify(thresholdMet = true, readyPeers = 3, notAdvanced, PeersAheadProbe.failed),
        SuppressedBy.ProbeError
      ),
      (
        "probe required but produced no result and no scheduling reason",
        classify(thresholdMet = true, readyPeers = 3, notAdvanced, PeersAheadProbe.none),
        SuppressedBy.ProbeError
      ),
      (
        "coordinator in flight: in_flight",
        classify(thresholdMet = true, readyPeers = 3, notAdvanced, PeersAheadProbe.none, scheduling = Some(SuppressedBy.InFlight)),
        SuppressedBy.InFlight
      ),
      (
        "coordinator cooldown (or residence gate): cooldown",
        classify(thresholdMet = true, readyPeers = 3, notAdvanced, PeersAheadProbe.none, scheduling = Some(SuppressedBy.Cooldown)),
        SuppressedBy.Cooldown
      ),
      (
        "threshold precedes a scheduling reason",
        classify(thresholdMet = false, readyPeers = 3, notAdvanced, PeersAheadProbe.none, scheduling = Some(SuppressedBy.InFlight)),
        SuppressedBy.Threshold
      ),
      (
        "no candidates precedes a scheduling reason",
        classify(thresholdMet = true, readyPeers = 0, notAdvanced, PeersAheadProbe.none, scheduling = Some(SuppressedBy.Cooldown)),
        SuppressedBy.NoCandidates
      ),
      (
        "rumor fast path with a scheduling reason still escalates: unsuppressed",
        classify(thresholdMet = true, readyPeers = 3, advanced, PeersAheadProbe.none, scheduling = Some(SuppressedBy.Cooldown)),
        SuppressedBy.Unsuppressed
      ),
      (
        "every sampled peer timed out or errored",
        classify(
          thresholdMet = true,
          readyPeers = 3,
          notAdvanced,
          probeOf(List(PeerResult.TimedOut, PeerResult.Errored, PeerResult.TimedOut), 3)
        ),
        SuppressedBy.NoResponders
      ),
      (
        "responders all strictly below the key",
        classify(thresholdMet = true, readyPeers = 3, notAdvanced, probeOf(List(meta(99L), meta(98L), PeerResult.Errored), 3)),
        SuppressedBy.RespondersBelowKey
      ),
      (
        "one identity at the key but only one corroborator of the required two",
        classify(thresholdMet = true, readyPeers = 3, notAdvanced, probeOf(List(meta(100L), meta(99L), PeerResult.TimedOut), 3)),
        SuppressedBy.InsufficientCorroborators
      ),
      (
        "one identity at the key with two corroborators but not a strict responder majority",
        classify(thresholdMet = true, readyPeers = 4, notAdvanced, probeOf(List(meta(100L), meta(100L), meta(99L), meta(99L)), 4)),
        SuppressedBy.InsufficientCorroborators
      ),
      (
        "same ordinal, different hashes at the corroborating ordinal",
        classify(thresholdMet = true, readyPeers = 3, notAdvanced, probeOf(List(meta(100L, "a"), meta(100L, "b"), meta(99L)), 3)),
        SuppressedBy.Disagreement
      ),
      (
        "different ordinals above the key are spread, not disagreement",
        classify(thresholdMet = true, readyPeers = 3, notAdvanced, probeOf(List(meta(101L, "a"), meta(102L, "b"), meta(99L)), 3)),
        SuppressedBy.InsufficientCorroborators
      ),
      (
        "corroborated committed progress escalates: unsuppressed",
        classify(thresholdMet = true, readyPeers = 3, notAdvanced, probeOf(List(meta(100L), meta(100L), meta(99L)), 3)),
        SuppressedBy.Unsuppressed
      ),
      (
        "rumor fast path escalates with no probe: unsuppressed",
        classify(thresholdMet = true, readyPeers = 3, advanced, PeersAheadProbe.none),
        SuppressedBy.Unsuppressed
      )
    )

    table.foldLeft(success) {
      case (acc, (label, actual, expected)) =>
        acc.and(expect(actual == expected, s"$label: expected ${expected.label}, got ${actual.label}"))
    }
  }

  pureTest("the corroborating group is reported deterministically and distinguishes disagreement from spread") {
    val disagreement = probeOf(List(meta(100L, "a"), meta(100L, "b"), meta(100L, "b"), meta(99L)), 4)
    val spread = probeOf(List(meta(101L, "a"), meta(102L, "b"), meta(99L)), 3)

    expect
      .same(Some(100L), disagreement.corroboratingOrdinal)
      .and(expect.same(2, disagreement.corroboratingPeers))
      .and(expect.same(2, disagreement.hashesAtCorroboratingOrdinal))
      .and(expect.same(2, disagreement.aheadGroups))
      .and(expect.same(1, disagreement.belowKeyPeers))
      .and(expect.same(3, disagreement.atKeyPeers))
      .and(expect.same(0, disagreement.aboveKeyPeers))
      .and(expect(!disagreement.confirmedAhead, "2 of 4 responders is not a strict majority: decision unchanged"))
      .and(expect.same(Some(102L), spread.corroboratingOrdinal))
      .and(expect.same(1, spread.hashesAtCorroboratingOrdinal))
      .and(expect.same(2, spread.aboveKeyPeers))
  }

  pureTest("per-peer local results are counted separately from responders") {
    val probe = probeOf(List(PeerResult.TimedOut, PeerResult.Errored, PeerResult.Errored, meta(99L)), 4)

    expect
      .same(1, probe.timedOutPeers)
      .and(expect.same(2, probe.erroredPeers))
      .and(expect.same(1, probe.respondedPeers))
      .and(expect.same(4, probe.probedPeers))
      .and(expect.same(2, probe.requiredCorroborators))
      .and(expect.same(ProbeOutcome.Completed, probe.outcome))
  }

  pureTest("the locked-attempt classifier maps a corroborated probe to none and an empty sample to no_candidates") {
    expect
      .same(SuppressedBy.Unsuppressed, AbandonmentTracker.probeSuppressedBy(probeOf(List(meta(100L), meta(100L)), 2)))
      .and(expect.same(SuppressedBy.NoCandidates, AbandonmentTracker.probeSuppressedBy(probeOf(Nil, 0))))
      .and(expect.same(SuppressedBy.ProbeTimeout, AbandonmentTracker.probeSuppressedBy(PeersAheadProbe.timedOut)))
  }

  pureTest("the enum is bounded and its labels are stable metric label values") {
    val labels = SuppressedBy.values.map(_.label)
    expect
      .same(
        List(
          "threshold",
          "no_candidates",
          "in_flight",
          "cooldown",
          "probe_timeout",
          "probe_error",
          "no_responders",
          "responders_below_key",
          "insufficient_corroborators",
          "disagreement",
          "protected_lock_or_certified_transition",
          "state_transition_failed",
          "none"
        ),
        labels
      )
      .and(expect(labels.distinct.size == labels.size, "labels are unique"))
  }
}
