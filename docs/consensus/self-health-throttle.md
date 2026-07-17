# Self-health throttle for leader rotation

**Status:** Implemented and live. Landed as the consensusSchemaVersion v15
tiering; the live schema is now 33 (`config/types.scala:830`). The mechanism
is active in the leader-selection path on both dag-l0 and currency-l0 (the
citations below are the dag-l0 sites; currency-l0 mirrors them in
`CurrencySnapshotConsensusStateCreator.scala` / `...Advancer.scala`). Wiring:

- `SelfHealthHint` ADT: `infrastructure/selfhealth/SelfHealthHint.scala:26-47`
- `LocalHealthMonitor` (polls GC/load, derives the hint, exports gauges):
  `infrastructure/selfhealth/LocalHealthMonitor.scala:65-105,198-210`
- `Facility.selfHealthHint`: `consensus/declaration.scala:60`, captured at
  Facility-build time from `LocalHealthMonitor.current`
  (`GlobalSnapshotConsensusStateCreator.scala:497,510`)
- `Proposal.observedSelfHealth` field: `consensus/declaration.scala:460`. The
  leader aggregates each responder's `Facility.selfHealthHint` into it in
  `toProposalsPhase` (`GlobalSnapshotConsensusStateAdvancer.scala:932-934`)
- Carried forward as `ConsensusOutcome.peerSelfHealth`:
  `GlobalSnapshotConsensusStateAdvancer.scala:678` (from the accepted
  proposal's `observedSelfHealth`, REPLACE-on-accept)
- Consumed by next round's `selectLeaderWeighted`:
  `FacilitatorSelector.scala:216,238-275`, called at
  `GlobalSnapshotConsensusStateCreator.scala:680-690` with
  `selfHealthHints = controllerInputs.selfHealth`, sourced from the
  carried-forward `lastOutcome.peerSelfHealth`
  (`GlobalSnapshotConsensusStateCreator.scala:314`)

The rest of this document is the original design rationale, retained as a
reference. Where the shipped behavior diverged from the proposal it is called
out inline; in particular the leader-pool decision (Open Decision #1) shipped
as **hard exclude with starvation fallback**, not the "strong demote" the
proposal favored. See [shipped selection semantics](#shipped-selection-semantics).

## Motivation

Alpha.73 overnight (2026-05-14/15) confirmed that the v14 binary-band rotates leadership across all peers above the 50% completion ratio -- including six community peers with documented hardware issues (8804651b's 81s max GC pause; 90eb1ed3's load1m=54 on 8 vCPU; etc). When one of those leads during a GC pause the round abandons, committee shrinks, and the v18 `peersAtHigherKey=0` gate then deadlocks recovery.

v14's quality scoring is correctness-only (completion ratio). It has no latency-tail awareness, and the underlying ratchet -- a peer's `completed` count grows only when it's credited in another leader's `observedResponders` -- only catches *chronic* slowness across many rounds. A peer that completes 90/100 rounds but takes 80s on the 10th is still tier 0.

The cleanest functional fix is to let the peer itself signal "I'm in a bad state right now, deprioritize me as leader." Operators can already see this in their own JVM metrics; what they cannot do today is feed that observation into the cluster's leader rotation.

## Goals

1. **Functional, not just observational.** When a peer's local JVM/system is degraded, the cluster should pick a different leader.
2. **Adversary-resistant.** A peer that lies "healthy" when actually degraded should be self-corrected by the existing quality ratchet; a peer that lies "degraded" should pay an opportunity cost (lost leader slots) without gaining anything.
3. **Determinism preserving.** All honest nodes must compute the same leader. The hint must enter consensus-agreed state.
4. **Modest scope.** One new module, one schema field, one selector branch. No new gossip channel.
5. **Backward-compat NOT required.** Testnet deploys are all-or-nothing per [[feedback_testnet_no_rolling_upgrades]]; a cluster-wide cold restart at the schema bump is acceptable.

## Non-goals (for this iteration)

- Cross-peer verification of self-health (network can't observe whether 8804651b's `Healthy` claim is truthful in real time). We rely on the quality ratchet for that.
- Self-health affecting *facilitator* selection. Degraded peers still facilitate / sign / witness; we only deprioritize the leader role.
- Self-health affecting *eviction*. Degraded is reversible; eviction is heavyweight. Keep them separate.
- Persisting self-health across snapshots. Rebuild from each round's facilities. A cold-started cluster sees no hints until the first round of facilities arrives.

## Mechanism

```
LocalHealthMonitor (periodic, ~10s)
  - Polls ManagementFactory.getGarbageCollectorMXBeans for GC pauses
  - Polls OperatingSystemMXBean.getSystemLoadAverage
  - Computes SelfHealthHint = Healthy | Degraded | Critical
  - Updates Ref[F, SelfHealthHint]
              |
              | read at Facility-build time
              v
Facility (schema v15)
  + selfHealthHint: Option[SelfHealthHint] = None
              |
              | gossiped, collected by leader during CollectingFacilities
              v
ConsensusState.facilities: SortedMap[PeerId, Facility]
              |
              | extracted into ConsensusOutcome on round completion
              v
ConsensusOutcome
  + peerSelfHealth: Map[PeerId, SelfHealthHint]
              |
              | consumed by next round's leader selection
              v
selectLeaderWeighted(facilitators, entropy, qualityScores, selfHealthHints, ...)
  - Critical  -> tier 2 (selected only if no tier 0/1 exists)
  - Degraded  -> tier 1
  - Healthy   -> existing tier 0/1 logic based on completion ratio
```

### SelfHealthHint

```scala
sealed trait SelfHealthHint { def label: String }
object SelfHealthHint {
  case object Healthy  extends SelfHealthHint { val label = "healthy" }
  case object Degraded extends SelfHealthHint { val label = "degraded" }
  case object Critical extends SelfHealthHint { val label = "critical" }
}
```

Three states (not a continuous score) so the boundary is observable from the
metric label set. The shipped per-node gauge is
`dag_node_self_health{state}` (`LocalHealthMonitor.scala:214-221`): per node, a
1.0 on the active hint and 0.0 on the others. There is NO `peer_id` label and
NO cluster-observed per-peer metric -- the promised
`dag_consensus_peer_self_health{peer_id, state}` was never built. To alert on a
critically-degraded node, scrape every peer and match
`dag_node_self_health{state="critical"} == 1`.

### LocalHealthMonitor signals

For the initial implementation, derive `SelfHealthHint` from three local signals:

| Signal | Source | Degraded if | Critical if |
|---|---|---|---|
| GC pause max (last 5 min) | `GarbageCollectorMXBean.getCollectionTime` deltas | > 5s | > 30s |
| Load1m / vCPU | `OperatingSystemMXBean.getSystemLoadAverage` / `availableProcessors` | > 3.0 | > 6.0 |
| Recent round p95 (when self led) | rolling sample from `dag_consensus_round_completed_total` durations | > 30s | > 60s |

> Shipped divergence: `LocalHealthMonitor.deriveHint`
> (`LocalHealthMonitor.scala:198-210`) uses only the GC-pause and load signals.
> The recent-round-p95 row was NOT wired into the hint derivation;
> `HealthSnapshot.recentLeaderRoundP95Ms` is captured for dashboards but does
> not influence `SelfHealthHint`.

Thresholds are config-tunable (`LocalHealthMonitorConfig`, `config/types.scala:82-93`;
defaults `gcPauseDegradedMs=5000`, `gcPauseCriticalMs=30000`,
`loadPerVcpuDegraded=3.0`, `loadPerVcpuCritical=6.0`, `pollInterval=10s`).
Defaults derived from the alpha.73 metrics-deep-dive:
- 8804651b's 81s GC pause clearly Critical.
- 90eb1ed3's 54/8 load = 6.75 per vCPU. Critical.
- 9561959b's 6.5s GC pause + 39/8 load = 4.88 per vCPU. Degraded.
- Source nodes (.193/.45/.79) at 1ms GC, ~1 load/vCPU = Healthy.

Per [[feedback_env_dependent_config_pattern]] use `Map[AppEnvironment, Thresholds]`.

### Schema bump (consensusSchemaVersion v14 -> v15, shipped)

This field shipped at schema v15; the live `consensusSchemaVersion` is now 33
(`config/types.scala:830`). The shipped `Facility` is in
`consensus/declaration.scala:60` (`selfHealthHint: Option[SelfHealthHint] = None`).

```scala
case class Facility(
  eventHashes: Set[Hash],
  candidates: Candidates,
  trigger: Option[ConsensusTrigger],
  facilitatorsHash: Hash,
  lastGlobalSnapshotOrdinal: SnapshotOrdinal,
  lastSnapshotHash: Hash,
  consensusConfigHash: Option[Hash] = None,
  selfHealthHint: Option[SelfHealthHint] = None    // NEW
) extends PeerDeclaration
```

Optional with default None so v14 and v15 peers wire-decode each others' messages, but per [[reference_jar_hash_vs_schema_hash]] the jar hash gate already prevents cross-version connections; we don't rely on the optionality for compat.

`deterministicConfigHash` adds the relevant LocalHealthMonitor thresholds so divergent operator configs can't silently produce divergent leader selection.

### Shipped selection semantics

> NOTE: the proposal's original pseudocode below (a single sorted pool that
> demotes Critical to tier 2) is NOT what shipped. The shipped
> `selectLeaderWeighted` (`FacilitatorSelector.scala:211-275`) is a two-stage
> filter-then-tier. Critical-self-report peers are HARD-EXCLUDED from the
> leader-eligible pool, not tier-2 demoted, and the shipped signature carries
> two more terms (`hardLeaderQualityScorePct`, `peerViewChanges`) that the
> proposal omitted. Read this subsection, not the pseudocode, for current
> behavior.

Stage 1 -- hard eligibility filter (`FacilitatorSelector.scala:238-249`). A
peer is dropped from leader candidacy when EITHER:

- it self-reports `Critical` (`hint != SelfHealthHint.Critical` gate), OR
- it falls below `hardLeaderQualityScorePct` (default 20) on the integer
  quality score, which is `(completed / participated)` adjusted down by the
  view-change-caused rate: `completed * (participated - viewChangesCaused) * 100
  >= hardLeaderQualityScorePct * participated^2`. `viewChangesCaused` (from
  `peerViewChanges`) is clamped to `[0, participated]`. Peers with no history
  (`participated == 0`) pass.

Filtered-out peers stay full committee members for voting / signing /
witnessing; they only lose leader candidacy.

Starvation fallback (`FacilitatorSelector.scala:251`): if the filtered pool has
fewer than `minLeaderPoolSize` (default 2) peers, the filter is bypassed and the
full input `facilitators` set is used. This is the ONLY path by which a Critical
peer can be elected leader -- it is never reached by normal demotion.

Stage 2 -- tier sort within the surviving pool (`FacilitatorSelector.scala:259-272`):

- tier 0: `Healthy` and leader-eligible (completion ratio >= `minLeaderRatioPct`,
  or no history)
- tier 1: `Healthy` but below the ratio threshold, OR `Degraded` self-report
- tier 2: `Critical` self-report (only present at all via the starvation
  fallback above)

Within a tier, rendezvous score (entropy-dependent) breaks ties, so view 0
spreads leadership across the eligible pool. The final leader is
`sorted(viewNumber % sorted.size)`. All arithmetic is integer-only for
cross-platform determinism.

The proposal's pseudocode (historical, superseded -- demotes rather than
excludes Critical):

```scala
// SUPERSEDED -- see "Shipped selection semantics" above for actual behavior.
def selectLeaderWeighted(
  facilitators: List[PeerId],
  entropy: Hash,
  viewNumber: Int = 0,
  qualityScores: Map[PeerId, (Int, Int)] = Map.empty,
  selfHealthHints: Map[PeerId, SelfHealthHint] = Map.empty,  // NEW
  minLeaderRatioPct: Int = 50
): PeerId = {
  val sorted = facilitators.sortBy { pid =>
    val rendezvous = FacilitatorSelector.rendezvousScore(pid.value.value, entropy.value)
    val (completed, participated) = qualityScores.getOrElse(pid, (0, 0))
    val hint = selfHealthHints.getOrElse(pid, SelfHealthHint.Healthy)

    val tier: Long = hint match {
      case SelfHealthHint.Critical => 2L
      case SelfHealthHint.Degraded => 1L
      case SelfHealthHint.Healthy =>
        if (participated == 0) 0L
        else if (completed.toLong * 100L >= participated.toLong * minLeaderRatioPct.toLong) 0L
        else 1L
    }
    (tier, rendezvous)
  }
  sorted(viewNumber % sorted.size)
}
```

## Adversarial analysis

| Lie | Outcome | Net effect |
|---|---|---|
| "Healthy" when degraded | Gets elected leader. Round abandons during peer's GC pause. `completed` count stalls relative to `participated`. Quality ratio drops. After ~10-20 rounds the peer crosses below `minLeaderRatioPct` and drops to tier 1 via the existing ratchet. | Self-correcting in O(N) rounds. |
| "Degraded" when healthy | Always demoted to tier 1. Loses leader opportunities forever. Still participates as a facilitator / signer / witness and retains its delegated validator share while seated. | No advantage to lying. |
| "Critical" when healthy | Even stronger self-penalty. Same direction as above. | No advantage. |

**Incentive-compatible:** every lie either self-corrects or costs the liar. No lie shifts work or rewards onto honest peers in a way the protocol can't recover from.

The lying-healthy case still pays the cluster cost of one abandoned round per ~5 GC-pause windows. That cost is bounded by the same quality ratchet that already exists; self-health just makes it *one-shot*-detectable instead of statistical.

## Verifiability / measurement

The proposal does NOT depend on the network being able to verify the self-report in real time. Two layers of after-the-fact verification:

1. **Quality ratchet (in-protocol, already exists).** A peer that consistently self-reports Healthy but gets credited only 30% of the time in `observedResponders` lands below the chronic-classifier threshold and is excluded from facilitator selection. v18 + v17 are still on the path.

2. **Operator dashboard (out-of-protocol).** Shipped metrics:
   ```
   dag_node_self_health{state}                       # per-node hint (1.0 on active state); scrape every peer
   dag_node_self_health_gc_pause_max_recent_seconds  # underlying GC signal
   dag_node_self_health_load_per_vcpu                 # underlying load signal
   dag_consensus_round_abandoned_by_leader_total      # AbandonmentTracker.scala:333
   ```
   `dag_node_self_health` reports only the LOCAL node's own claim (no
   `peer_id` label); the leader's cluster-observed per-peer view was never
   exported as a metric. A node that reports `state="healthy"` while its
   observed quality ratio stays below 0.5 is the obvious liar pattern;
   operators prune.

So the answer to "how do we measure this reliably" is: **the JVM and OS signals are concrete and locally honest**; the *reporting* of them is unverified but rendered safe by the asymmetric incentive structure plus the existing quality ratchet.

## Open decisions (resolved by the shipped implementation)

1. **Critical = strong demote (tier 2 fallback) or hard exclude?** RESOLVED:
   shipped as **hard exclude with starvation fallback**, not the proposal's
   "strong demote". Critical peers are filtered out of the leader-eligible pool
   (`FacilitatorSelector.scala:249`) and only re-enter when the filtered pool
   drops below `minLeaderPoolSize`, which preserves liveness in an all-Critical
   cluster. See [shipped selection semantics](#shipped-selection-semantics).

2. **Polling cadence.** 10s is the proposal. Faster than 5s starts to cost CPU; slower than 30s misses transient pauses. Open to tuning.

3. **Should selfHealthHint also feed witness / cert-pool eligibility (v17 territory)?** Proposing NO -- keep degraded peers in the witness pool so cert assembly stays robust. Self-health only deprioritizes leader role.

4. **Persistence across cold-restart.** Proposing NO -- rebuild from each round's facilities, accept that fresh-deployed cluster picks leaders without hints until first Facility round completes. The alternative (persist on snapshot like v20) is a much bigger schema change.

5. **Smaller MVP first?** Option: ship just the LocalHealthMonitor + operator-config-based override (`config.localHealthOverride: Option[SelfHealthHint]`), no schema bump, no auto-detect. Lets operators manually pin `Critical` on the 6 bad community peers as a stop-gap while the auto-detection bakes. Then iterate to auto-detect in v15.

## Implementation plan (DONE)

All three phases shipped. The phase outline is retained as a map of where the
mechanism lives; the per-line citations in the status banner at the top are
authoritative.

**Phase A: local infrastructure** (no consensus impact, no schema bump)
- `LocalHealthMonitor.scala` in node-shared (~150 lines + tests)
- `SelfHealthHint` ADT + JSON codecs
- Wire into `SharedServices.scala` via `Ref[F, SelfHealthHint]`
- Prometheus gauge `dag_node_self_health{state}` + underlying signals
- **Ships independently. Observable only.**

**Phase B: protocol carry**
- Added `selfHealthHint` to `Facility` schema (`consensus/declaration.scala:60`;
  aggregation-comment at `declaration.scala:54-59`)
- Builds the Facility reading from `LocalHealthMonitor.current`
  (`GlobalSnapshotConsensusStateCreator.scala:497,510`)
- Added `peerSelfHealth` to `ConsensusOutcome`, populated at
  `GlobalSnapshotConsensusStateAdvancer.scala:678`
- Leader aggregates per-facilitator hints into `Proposal.observedSelfHealth`
  in `toProposalsPhase` (`GlobalSnapshotConsensusStateAdvancer.scala:932-934`;
  field at `consensus/declaration.scala:460`)
- NOTE: the proposed `dag_consensus_peer_self_health{peer_id, state}` outcome
  metric was NOT built; only the per-node `dag_node_self_health{state}` exists.

**Phase C: leader selection consumes self-health**
- `selectLeaderWeighted` carries `selfHealthHints` (plus the additional
  `peerViewChanges` / `hardLeaderQualityScorePct` terms);
  `FacilitatorSelector.scala:211-275`
- Called with `selfHealthHints = controllerInputs.selfHealth` at
  `GlobalSnapshotConsensusStateCreator.scala:680-690`; the advancer recomputes
  the same leader deterministically at
  `GlobalSnapshotConsensusStateAdvancer.scala:629`

## References

- [[project_v14_overnight_analysis_may15]] -- the causal chain this proposal addresses
- [[reference_metric_label_convention]] -- use `peer_id` not `peer_id_short`
- [[project_v18_abandon_gate_may11]] -- the gate that wedges when this proposal's degraded-peers cascade out
- [[reference_jar_hash_vs_schema_hash]] -- jar hash is the version gate, not schemaVersion
- [[feedback_env_dependent_config_pattern]] -- `Map[AppEnvironment, ...]` for thresholds
- [[feedback_testnet_no_rolling_upgrades]] -- cluster-wide cold restart at Phase B
