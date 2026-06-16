# Coordinated cluster restart signaling (proposal, deferred)

Status: proposal, not yet implemented (deferred). Documents the user-proposed design from the 2026-05-15 self-health discussion. None of the mechanism below exists in code (no `clusterIntent` / `CoordinatedRestart` / `RestartGroup` / `WaitingForCoordinatedRestart` / `coordinatorPeerIds` under `modules/*/src/main`). Its main precondition -- the self-health throttle (v15) -- has since shipped (see [self-health-throttle.md](self-health-throttle.md), Status: IMPLEMENTED), so the remaining blockers are the node-pilot SDK surface and a design sign-off rather than missing dependencies.

## Motivation

When the cluster needs to be cold-restarted -- a schema bump, an operator-detected runaway state, a quorum-infeasible wedge that the v18 gate can't escape -- the current procedure is "operator hard-kills JVMs on every peer and restarts them." That has two failure modes the cluster could solve for itself:

1. **No coordination on come-back order.** All peers race to handshake. Whoever lands first becomes the cluster, and slow / hardware-marginal peers can win the race by happenstance. The freshly-restarted cluster inherits the same bad-peer composition that may have caused the wedge.
2. **No coordination on shutdown timing.** Some peers see the kill signal earlier than others. A clean-shutdown peer that has finalized round N is ahead of a hard-kill peer that abandoned round N. After restart, the lagging peer triggers a recovery storm.

Both classes of pain were observed across the alpha.5x and alpha.7x deploys this quarter. node-pilot today issues hard kills; a coordinated path would let the cluster nominate its best peers and re-converge cleanly.

## Concept (user-proposed shape)

Add an optional `clusterIntent` field to a consensus operation (Facility or Proposal, or a new `Heartbeat`-like declaration) that carries one of:

| Intent | Meaning |
|---|---|
| `Normal` (default) | Cluster operating normally. |
| `DegradedShutdown` | I observe sustained `recoverySuppressed=true`, planning to leave. Operators monitoring this metric can intervene. |
| `CoordinatedRestart { initiator, target_epoch }` | Signed command from a designated coordinator: every peer SHOULD reach a clean stopping point and idle. |
| `RestartGroup { members: Set[PeerId] }` | Signed proposal of which peers should come back online first, derived from quality scores + self-health history. |

The intent flows on the consensus path the same way Facility / Proposal flow today, so it inherits the cluster's normal aggregation, signing, and quorum properties. The "kind" model is borrowed from the user's suggestion of a snapshot-tagging variant (`Normal | Degraded | Restart`) so the same field can mean different things in different contexts.

## Architecture sketch

```
+------------------+      signs intent             +-----------------+
| designated       |---- ClusterIntent rumor ----> | every peer      |
| coordinator(s)   |                               +-----------------+
| (configured by   |                                       |
|  peer-id in HOCON|                                       | adopts
|  + jar-hash trust|                                       v
|  domain)         |                              +-----------------+
+------------------+                              | restart phase   |
                                                  | machine:        |
                                                  |  - quiesce      |
                                                  |  - idle         |
                                                  |  - rejoin per   |
                                                  |    RestartGroup |
                                                  +-----------------+
```

### Designated coordinators

Configured by `coordinatorPeerIds: List[PeerId]` in HOCON. A `ClusterIntent` rumor is accepted only when:

1. The rumor envelope signer is in `coordinatorPeerIds`.
2. The jar hash on the signing peer matches the cluster's current `consensusConfigHash` family (no cross-version coordinators).
3. The intent is `Normal` (informational, anyone can send) OR the signer is in the coordinator allowlist.

Coordinator set MUST be `>= 2` so a single compromised coordinator can't unilaterally shut down the cluster. `RestartGroup` proposals require quorum among coordinators, like a multi-sig.

### RestartGroup selection (cluster-computed)

Drawing on data the cluster already has:
- `lastOutcome.peerQuality.ratio` (existing)
- `lastOutcome.peerSelfHealth` (post-v15)
- `cumulativeMissCounts` (existing)
- Coordinator manual override list

Compute a deterministic `RestartGroup` by sorting peers by `(self_health_tier ASC, quality_ratio DESC, cumulative_miss_count ASC)` and taking the top `min(maxFacilitatorCount, eligibleFacilitators.size - tolerance)` peers. The deterministic ordering means every peer that observes the same outcome computes the same group.

### Restart phase machine

When a peer receives a quorum-signed `CoordinatedRestart`:

1. Stop accepting new client traffic.
2. Finalize any round in progress (or abandon cleanly).
3. Transition to a new `NodeState.WaitingForCoordinatedRestart`.
4. Idle. Don't try to re-join until the coordinator emits `RestartGroup.cleared` or a wall-clock timeout expires.
5. On clear: peers in `RestartGroup.members` come up first; others wait for those to reach `Ready` before re-handshake.

### Snapshot tagging variant (user's "kind of snapshot" idea)

Instead of adding a separate intent type, the snapshot artifact itself could carry a `kind: SnapshotKind` field with values `Normal | Degraded | RestartCheckpoint`. A `RestartCheckpoint` is a deliberate clean-state anchor at which the cluster is expected to halt -- the equivalent of a database WAL checkpoint that an operator can roll back to without losing committed transactions. Coordinator emits the intent, cluster produces one final `RestartCheckpoint` snapshot, all peers halt at that anchor. Restart resumes from the checkpoint.

This is more elegant than a separate gossip type because the checkpoint is signed by the same proof set as the normal snapshot, so cross-cluster trust is identical.

## Precedence

| System | Mechanism | Relevant lesson |
|---|---|---|
| Aptos | Epoch transitions are governed by on-chain reconfiguration; validator set rotation is a first-class event. | Coordinated restart can be modelled as a special epoch boundary. |
| Flow Cadence | Staking lifecycle: peers explicitly stake, unstake, exit. | Voluntary exit is a known signal, not just a hard kill. |
| Cosmos / Tendermint | Chain halts at a coordinator-issued block height (`halt_height` in genesis). Operators agree on the halt height; chain stops cleanly. | Exact precedent for `RestartCheckpoint`. |
| Ethereum hard forks | Activation height + signed proposal. All clients adopt at the height. | The "coordinated cold restart" we already informally do. |
| ZooKeeper / etcd | Leader-coordinated quorum reconfiguration. | Coordinator quorum (>= 2) is the established safety pattern. |

The closest match is Cosmos `halt_height`. Worth reading their implementation before designing ours.

## Integration touchpoints

- **node-pilot:** currently issues hard kills via the SDK. Would need a new command-line surface to send a `CoordinatedRestart` intent instead. Hard-kill stays as a fallback for true emergencies.
- **monitoring dashboards:** new gauges `dag_node_cluster_intent{state}` and `dag_node_coordinated_restart_pending` so the operator can see the cluster state at a glance.
- **/cluster/info:** expose the current intent and (when in `WaitingForCoordinatedRestart`) the expected `RestartGroup` membership. Pairs with task #100 (peer role visibility).
- **HOCON config:** `cluster.coordinatorPeerIds: List[PeerId]`, `cluster.coordinatorQuorumThreshold: Int`, `cluster.coordinatedRestart.idleTimeout: FiniteDuration`.

## Open questions

1. **Where does the intent live?** Embedded in Facility (low cost, every round carries it) vs separate Heartbeat-style declaration (more flexible, more wire bytes). Heartbeat is probably the cleaner answer because the cluster needs to send intent even when consensus is wedged.
2. **What happens if the coordinator peer ID itself is being evicted?** Reasonable answer: coordinator quorum is computed against `lastOutcome.facilitators` so an evicted peer drops out of the quorum naturally. Need to verify this doesn't deadlock.
3. **Re-handshake order from `RestartGroup`.** First-in-first-out by `PeerId`? Sorted by quality? Open.
4. **Wall-clock bounds on idle phase.** If the coordinator dies after emitting `CoordinatedRestart` and nothing clears the intent, peers will idle forever. Need a hard timeout (e.g. 30 min) after which peers self-clear and resume normal operation.
5. **Interaction with the v18 `peersAtHigherKey=0` gate.** If the cluster is wedged AND we issue a coordinated restart, the restart needs to bypass the recovery-suppression gate. Probably trivial: enter `WaitingForCoordinatedRestart` from any state.

## Scheduling

The v15 self-health throttle precondition has shipped (see [self-health-throttle.md](self-health-throttle.md)). Remaining gates before this work is picked up:
- node-pilot owner reviews the proposal and signs off on the SDK surface (node-pilot still issues hard kills today).
- Confirm the wedge pattern this proposal targets is not already covered by the chronic-classifier / self-health demotion paths now in place; if hardware-marginal peers are being demoted or evicted automatically, the marginal value of coordinated restart drops.

Estimated scope: ~2-3 weeks of focused work plus node-pilot SDK changes.

## References

- [[project_v14_overnight_analysis_may15]] -- the wedge pattern this proposal helps avoid
- [[project_v18_abandon_gate_may11]] -- the gate that needs the coordinated-restart bypass
- docs/consensus/self-health-throttle.md -- the v15 work this proposal builds on
