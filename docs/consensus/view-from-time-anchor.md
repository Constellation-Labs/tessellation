# View-from-Time Anchor (deterministic per-snapshot timestamp)

Design for adding a Byzantine-resilient, deterministic time anchor to each
global snapshot so the round-in-progress can derive its current view from
wall-clock without requiring proposer-trust or per-validator clock tolerance.

**Status:** Implemented and live. Landed at consensusSchemaVersion v19 (phase 2)
on both dag-l0 and currency-l0; the live schema is now 33
(`config/types.scala:830`). Wiring:

- Producer: `ConsensusEndTime.compute` (median + monotonicity clamp):
  `ConsensusEndTime.scala:47-62`, called at
  `GlobalSnapshotConsensusStateAdvancer.scala:957-958`; the trimmed
  `recentRoundEndTimes` window is written at
  `GlobalSnapshotConsensusStateAdvancer.scala:692`.
- Consumer: `ViewFromTime.compute`: `ViewFromTime.scala:36-45`, called at
  `GlobalSnapshotConsensusStateCreator.scala:674-675` off the parent's last
  `recentRoundEndTimes` entry.
- `Facility.proposerClockMs`: `consensus/declaration.scala:73`, captured via
  `Clock[F].realTime` at `GlobalSnapshotConsensusStateCreator.scala:501`.
- `ConsensusOperationalState.recentRoundEndTimes`:
  `schema/ConsensusOperationalState.scala:160`.
- `ConsensusEndTime.scala:31` back-references this doc via `@see`.

The algorithm, determinism, and prior-art sections below remain an accurate
design reference. The forward-looking Migration / streaming-deploy / alpha.82
sections are historical release notes; see the inline notes that reconcile them
with the shipped state.

---

## Motivation

`ConsensusRoundRunner.priorAbandonmentCount` reads `viewChangeVotes.maxToView`
to seed the next round's starting view. With phase 1 preserving that map
across abandonment, accumulated votes give a soft monotonic tick driven by
vote history.

But that tick depends on votes existing in the map. If a round abandons with
zero accumulated VCVs (e.g., the leader fails fast before any peer emits a
view-change vote), the next retry still starts at view=0. The durable answer
is to derive view from a deterministic timestamp on the parent snapshot:

```
view_in_progress = floor((local_now - parent.consensusEndTime) / viewInterval)
```

All facilitators reading the same parent snapshot compute the same view at
the same `local_now`. NTP skew across AWS-hosted nodes is typically +/- 10ms,
which causes ms-scale disagreement at view-transition boundaries; the gossip
layer absorbs that within one viewInterval.

The hard part is making `consensusEndTime` deterministic across signers
without introducing a clock-tolerance check.

---

## Approach: facility-set median (simplified Tendermint BFT-time)

Tendermint's canonical block time is the voting-power-weighted median of
validator commit timestamps. We adopt the same idea but drop the
voting-power weight because our facilitators are equal-weight:

1. Each `Facility` signed by a facilitator carries a new field:
   `proposerClockMs: Option[Long]` -- facilitator's local wall-clock at
   signing time. Acquired effectfully via `Clock[F].realTime.map(_.toMillis)`
   (matching the established pattern at
   `infrastructure/gossip/event/EventGossipDaemon.scala:142`). No bucketing
   on the wire: raw millis. The median absorbs outliers; downstream
   `view_in_progress = floor((now - parent) / viewInterval)` already
   coarse-grains for view-transition purposes.
2. At consensus completion, the canonical
   `consensusEndTime = max(median(facilities.map(_.proposerClockMs)), parent.consensusEndTime + 1)`.
   The `max` clamp guarantees monotonicity even if NTP corrections shift
   facilitator clocks backwards (Bitcoin MTP-style anti-regression).
3. `consensusEndTime` is stored on the next snapshot's
   `ConsensusOperationalState.recentRoundEndTimes`.

### Why median, not average

f Byzantine facilitators can shift the average by an arbitrary amount; they
can shift the median by at most their fraction. With committee size `n` and
`f < n/3`, the median sits between the timestamps of honest facilitators.

### Why per-Facility, not per-Proposal

Facilities are already aggregated and signed independently before the
leader's proposal goes out. Threading the timestamp through the existing
Facility schema costs one field. Putting it on Proposal would gate the
timestamp on the leader-elected peer, reintroducing the proposer-trust gap
we're trying to avoid.

### Why no validator tolerance check

The median is robust to outliers by construction. A malicious facilitator
who reports `clockMs = 0` or `clockMs = Long.MaxValue` shifts the median by
at most one order-statistic step. The honest majority's clocks pin the
median. No `|proposer.clock - my.clock| < tolerance` gate is needed in
`validateArtifact` re-execution; the median is deterministic on the agreed
Facility set, and the Facility set is already consensus-validated.

### Monotonicity clamp

Phantom regression scenario: a node experiences an NTP correction
mid-consensus, its `proposerClockMs` reports a smaller value than the previous
round. If enough nodes correct in the same direction, the raw median could
decrease. The `max(median, parent.consensusEndTime + 1)` clamp at consume
site (not at signing site) handles this deterministically without rejecting
the proposal. Equivalent to Bitcoin's median-time-past anti-regression rule.

---

## Schema changes

### 1. `Facility` (node-shared, the per-facilitator declaration schema)

Add an `Option`-wrapped field following the established back-compat pattern:

```scala
final case class Facility(
  // ... existing fields ...
  proposerClockMs: Option[Long] = None
)
```

The `Option` wrap is mandatory per the derevo-decoder caveat documented in
`ConsensusOperationalState.scala` lines 127-136. With `dropNullValues = true`,
pre-v19 Facilities (no field) decode identically to v19 Facilities carrying
`None`.

Facilities written by alpha.82+ peers carry `Some(currentTimeMillis)`.
Facilities written by older peers (during a partial-deploy window) decode as
`None`. The median computation must skip `None` values; if fewer than
`n/2 + 1` Facilities carry a clock, the median is `None` and
`recentRoundEndTimes` is not populated for that ordinal -- view continues to
derive from `viewChangeVotes.maxToView` (phase 1 mechanism) in that case.

### 2. `ConsensusOperationalState`

Add a sibling Option-wrapped field next to `recentSigners` in
`modules/shared/src/main/scala/io/constellationnetwork/schema/ConsensusOperationalState.scala`:

```scala
final case class ConsensusOperationalState(
  perPeer: SortedMap[PeerId, PerPeerOperationalRecord],
  recentProofSizes: SortedMap[SnapshotOrdinal, Int],
  recentSigners: Option[SortedMap[SnapshotOrdinal, SortedSet[PeerId]]] = None,
  recentRoundEndTimes: Option[SortedMap[SnapshotOrdinal, Long]] = None
)
```

Bounded window: keep last K ordinals (same K as `recentSigners`). On
finalization, append `(ordinal -> consensusEndTime)` and prune outside the
window.

### 3. Consensus schema version bump

This shipped at `consensusSchemaVersion` v19; the live value is now 33
(`config/types.scala:830`). The schema value is folded into
`deterministicConfigHash`; that hash and the reported release-version hashes
fence peer connections. The advertised jar hash is not compared
(`consensus/declaration.scala:67-72` notes the field was `Option`-wrapped for
derevo back-compat, not for runtime mixed-version interop).

### 4. Currency-l0 mirror

The mechanism is layer-agnostic. Mirror the change to currency-l0 in the
same release, same Option-wrap pattern. Same precedent as v22 active-set
tightening which landed on both layers together. Skipping currency-l0 would
diverge the two layers' time mechanics.

---

## Validation logic

In `validateArtifact` re-execution:

```scala
def computeConsensusEndTime(
  facilities: List[Signed[Facility]],
  parentEndTime: Long
): Option[Long] = {
  val clocks = facilities.flatMap(_.value.proposerClockMs).sorted
  val minRequired = facilities.size / 2 + 1
  if (clocks.size < minRequired) None
  else {
    val median = clocks(clocks.size / 2)
    Some(math.max(median, parentEndTime + 1))
  }
}
```

Every facilitator independently recomputes the median from the agreed
Facility set; the result is byte-identical across signers, so signature
aggregation succeeds. The clamp uses the parent snapshot's
`consensusEndTime` (read from the prior `recentRoundEndTimes` entry) -- also
deterministic.

---

## Configuration

```hocon
consensus {
  view-interval = 60s   # divisor for floor((now - parentEndTime) / viewInterval)
}
```

The live default is `view-interval = 60s` (`config/types.scala:295`), raised
from the originally-proposed 30s on 2026-05-28. The 30s value fired a wasted
timestamp-pacemaker VCV every round: median round duration (~45s) exceeded 30s,
so `floor((now - parentEndTime) / viewInterval)` evaluated to >= 1 during the
proposal phase on every round even though the round still finalized at view 0.
At 60s, `floor(45/60) = 0`, the per-round VCV disappears, and a genuine ~120s
stall still trips `timeView >= 2`. `viewInterval` is folded into
`deterministicConfigHash` (`config/types.scala:980`, `viewIntervalMs=...`), so
divergent operator values reject each other at peer connection.

There is NO `recent-round-end-times-window` config key. The retained-ordinals
window is pinned in code to `tighteningWindow` (the same K as `recentSigners`;
documented at `schema/ConsensusOperationalState.scala:150`, trimmed at
`GlobalSnapshotConsensusStateAdvancer.scala:497-500`) and is not independently
configurable.

No exponential backoff initially -- can be added after observing the simple
version's failure modes. Aptos's Pacemaker uses adaptive backoff but they
also have sub-second per-block latency; our slower consensus does not need
the extra complexity yet.

---

## Migration (historical release notes)

> These sections are point-in-time alpha.82 release notes, retained for
> context. The mechanism is fully deployed (schema v19, live schema 33); the
> mixed-version window described below is closed.

Phase 2 shipped as alpha.82 with both new fields Option-wrapped. Mixed-version
window behavior:

- Pre-alpha.82 peers: encode Facility without `proposerClockMs`. Their
  Facilities decode on alpha.82 peers as `proposerClockMs = None`.
- alpha.82 peers: encode `Some(currentTimeMillis)`. Decode either form
  transparently.
- Median computation requires at least `n/2 + 1` non-None values. During
  partial deploy, if too few alpha.82 peers are in the committee, the median
  is `None` and `recentRoundEndTimes` for that ordinal is not populated.
  View continues to derive from `viewChangeVotes.maxToView` (phase 1
  mechanism) until the cluster is fully on alpha.82.

Once all peers are alpha.82, the median is always computable. From there:

```
view_in_progress = floor((local_now - parent.recentRoundEndTimes.lastValue) / viewInterval)
```

becomes the primary view derivation, with phase 1's vote-driven tick as
fallback.

---

## Streaming impact (historical release notes)

> Retained for context; this was the alpha.82 deploy procedure. The fields are
> already live.

Same flow as the alpha.81 deploy:

1. `RELEASE_TAG=v4.1.0-alpha.82 sbt sdk/publishLocal` in tessellation.
2. Bump `tessellation = "4.1.0-alpha.82"` in
   `snapshot-streaming/project/Dependencies.scala`.
3. `GITHUB_TOKEN="$(gh auth token)" sbt assembly` in snapshot-streaming.
4. SCP the new jar to `ec2-user@13.57.169.30`.
5. Commit the `Dependencies.scala` bump on snapshot-streaming `release/testnet`.

Both new fields are `Option`-wrapped with `dropNullValues = true`, so
snapshots written by alpha.81 peers decode byte-identically on alpha.82
streaming and vice versa. The signature-verification regression that hit
the alpha.80 -> alpha.81 jump (caused by an un-wrapped `recentSigners`
field) does not recur with these wrapped additions.

---

## Prior-art references

- **Tendermint BFT-time**: `tendermint/spec/consensus/bft-time.md`. Weighted
  median of validator commit timestamps; our simplification drops the
  weight because facilitators are equal-weight.
- **Bitcoin median-time-past (BIP-113)**: median of past 11 block
  timestamps. The monotonicity clamp in `computeConsensusEndTime` matches
  Bitcoin's anti-regression behavior, applied to the single-facility-set
  median rather than a multi-block lookback.
- **Aptos `BlockMetadata.timestamp_usecs`**: proposer-set with tolerance
  check. Rejected here because it requires per-validator clock tolerance,
  which the median approach sidesteps.
- **Ethereum `allow_future_block_time_seconds = 15`**: bounded future
  tolerance; proposer-trust analog.

The facility-set median sits between Tendermint (most robust but heaviest
wiring) and Bitcoin MTP (simple but requires multi-block lookback). It is
the minimum-complexity Byzantine-resilient option for our equal-weight
facilitator model.
