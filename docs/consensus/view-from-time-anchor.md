# View-from-Time Anchor (deterministic per-snapshot timestamp)

Design for adding a Byzantine-resilient, deterministic time anchor to each
global snapshot so the round-in-progress can derive its current view from
wall-clock without requiring proposer-trust or per-validator clock tolerance.

Status: design accepted, not yet implemented. Targets the deploy after the
phase 1 `viewChangeVotes` preservation fix lands.

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
`ConsensusOperationalState.scala` lines 65-89. With `dropNullValues = true`,
pre-v22 Facilities (no field) decode identically to v22 Facilities carrying
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

Bump `consensusSchemaVersion: Int = 18` to `19` in
`node-shared/.../config/types.scala`. This is an audit-anchor only; the jar
hash already version-gates peer connections.

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
  view-interval = 30s              # local timer for time-derived view advance
  recent-round-end-times-window = 50  # ordinals retained in operational state
}
```

`view-interval = 30s` matches the observed alpha.81 abandon cadence (~45s
per abandon, force-VC fires after 3 abandons), so time-derived views
increment at roughly the same rate as today's vote-derived views. No
exponential backoff initially -- can be added after observing the simple
version's failure modes. Aptos's Pacemaker uses adaptive backoff but they
also have sub-second per-block latency; our slower consensus does not need
the extra complexity yet.

---

## Migration

Phase 2 ships as alpha.82 with both new fields Option-wrapped. Mixed-version
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

## Streaming impact

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
