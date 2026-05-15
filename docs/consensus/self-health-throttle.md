# Self-health throttle for leader rotation (proposal)

Status: proposal, not yet implemented. Targets consensusSchemaVersion v15.

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

Three states (not a continuous score) so the boundary is observable from the metric label set. Operators can write Prometheus alerts on `dag_consensus_peer_self_health{state="critical"} > 0`.

### LocalHealthMonitor signals

For the initial implementation, derive `SelfHealthHint` from three local signals:

| Signal | Source | Degraded if | Critical if |
|---|---|---|---|
| GC pause max (last 5 min) | `GarbageCollectorMXBean.getCollectionTime` deltas | > 5s | > 30s |
| Load1m / vCPU | `OperatingSystemMXBean.getSystemLoadAverage` / `availableProcessors` | > 3.0 | > 6.0 |
| Recent round p95 (when self led) | rolling sample from `dag_consensus_round_completed_total` durations | > 30s | > 60s |

Thresholds are config-tunable. Defaults derived from the alpha.73 metrics-deep-dive:
- 8804651b's 81s GC pause clearly Critical.
- 90eb1ed3's 54/8 load = 6.75 per vCPU. Critical.
- 9561959b's 6.5s GC pause + 39/8 load = 4.88 per vCPU. Degraded.
- Source nodes (.193/.45/.79) at 1ms GC, ~1 load/vCPU = Healthy.

Per [[feedback_env_dependent_config_pattern]] use `Map[AppEnvironment, Thresholds]`.

### Schema bump (consensusSchemaVersion v14 -> v15)

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

### selectLeaderWeighted change

```scala
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

Tier 2 is a true fallback: a cluster of all-Critical peers can still elect someone (avoids the cluster deadlocking if every peer self-reports critical at once). This is intentional symmetry with the chronic-band tier 1 design.

## Adversarial analysis

| Lie | Outcome | Net effect |
|---|---|---|
| "Healthy" when degraded | Gets elected leader. Round abandons during peer's GC pause. `completed` count stalls relative to `participated`. Quality ratio drops. After ~10-20 rounds the peer crosses below `minLeaderRatioPct` and drops to tier 1 via the existing ratchet. | Self-correcting in O(N) rounds. |
| "Degraded" when healthy | Always demoted to tier 1. Loses leader opportunities forever. Still participates as facilitator / signer / witness so reward share is mostly preserved. | No advantage to lying. |
| "Critical" when healthy | Even stronger self-penalty. Same direction as above. | No advantage. |

**Incentive-compatible:** every lie either self-corrects or costs the liar. No lie shifts work or rewards onto honest peers in a way the protocol can't recover from.

The lying-healthy case still pays the cluster cost of one abandoned round per ~5 GC-pause windows. That cost is bounded by the same quality ratchet that already exists; self-health just makes it *one-shot*-detectable instead of statistical.

## Verifiability / measurement

The proposal does NOT depend on the network being able to verify the self-report in real time. Two layers of after-the-fact verification:

1. **Quality ratchet (in-protocol, already exists).** A peer that consistently self-reports Healthy but gets credited only 30% of the time in `observedResponders` lands below the chronic-classifier threshold and is excluded from facilitator selection. v18 + v17 are still on the path.

2. **Operator dashboard (out-of-protocol, new metric).** Expose:
   ```
   dag_consensus_peer_self_health{peer_id, state}    # what the peer claimed last round
   dag_consensus_peer_quality_ratio{peer_id}         # what we observed (existing)
   dag_consensus_round_abandoned_by_leader_total{peer_id, reason}  # new in 4b76080ee
   ```
   A peer that reports Healthy with `ratio < 0.5` is the obvious liar pattern; operators prune.

So the answer to "how do we measure this reliably" is: **the JVM and OS signals are concrete and locally honest**; the *reporting* of them is unverified but rendered safe by the asymmetric incentive structure plus the existing quality ratchet.

## Open decisions (need user input before implementation)

1. **Critical = strong demote (tier 2 fallback) or hard exclude?** Strong demote keeps liveness in an all-Critical cluster; hard exclude is stricter but can deadlock. Proposing strong demote.

2. **Polling cadence.** 10s is the proposal. Faster than 5s starts to cost CPU; slower than 30s misses transient pauses. Open to tuning.

3. **Should selfHealthHint also feed witness / cert-pool eligibility (v17 territory)?** Proposing NO -- keep degraded peers in the witness pool so cert assembly stays robust. Self-health only deprioritizes leader role.

4. **Persistence across cold-restart.** Proposing NO -- rebuild from each round's facilities, accept that fresh-deployed cluster picks leaders without hints until first Facility round completes. The alternative (persist on snapshot like v20) is a much bigger schema change.

5. **Smaller MVP first?** Option: ship just the LocalHealthMonitor + operator-config-based override (`config.localHealthOverride: Option[SelfHealthHint]`), no schema bump, no auto-detect. Lets operators manually pin `Critical` on the 6 bad community peers as a stop-gap while the auto-detection bakes. Then iterate to auto-detect in v15.

## Implementation plan

If approved, three phases:

**Phase A: local infrastructure** (no consensus impact, no schema bump)
- `LocalHealthMonitor.scala` in node-shared (~150 lines + tests)
- `SelfHealthHint` ADT + JSON codecs
- Wire into `SharedServices.scala` via `Ref[F, SelfHealthHint]`
- Prometheus gauge `dag_node_self_health{state}` + underlying signals
- **Ships independently. Observable only.**

**Phase B: protocol carry**
- Add `selfHealthHint` to `Facility` schema (consensusSchemaVersion 14 -> 15)
- Update `GlobalSnapshotConsensusStateCreator.scala:477-488` and currency mirror to read from `LocalHealthMonitor.current` when building Facility
- Add `peerSelfHealth` field to `ConsensusOutcome`
- Extract self-health from `facilities` map at proposal-build time in advancer
- New metric `dag_consensus_peer_self_health{peer_id, state}` from outcome
- **Cluster-wide cold restart at this deploy** ([[feedback_testnet_no_rolling_upgrades]])

**Phase C: leader selection consumes self-health**
- Extend `selectLeaderWeighted` signature with `selfHealthHints` param
- Update call sites in `GlobalSnapshotConsensusStateCreator.scala:529-534` and currency mirror to pass `lastOutcome.peerSelfHealth`
- Update v14 test suite with self-health cases (lying healthy, lying degraded, all-critical fallback, boundary at threshold)

Phase A could ship this week as an observational-only change to validate the JVM polling without consensus risk. Phases B+C bundle into the next consensus schema bump.

## References

- [[project_v14_overnight_analysis_may15]] -- the causal chain this proposal addresses
- [[reference_metric_label_convention]] -- use `peer_id` not `peer_id_short`
- [[project_v18_abandon_gate_may11]] -- the gate that wedges when this proposal's degraded-peers cascade out
- [[reference_jar_hash_vs_schema_hash]] -- jar hash is the version gate, not schemaVersion
- [[feedback_env_dependent_config_pattern]] -- `Map[AppEnvironment, ...]` for thresholds
- [[feedback_testnet_no_rolling_upgrades]] -- cluster-wide cold restart at Phase B
