# Liveness Shrink: permissioned-fallback committee reduction

**Status:** SUPERSEDED - never implemented. Retained as historical context.
**Author:** scas (with Claude Opus 4.7)
**Date:** 2026-05-12
**Related:** `quorum-shrink.md` (the shipped mechanism), `eviction-cert-deterministic-shrinkage.md` (also superseded), `0006-selecting-facilitators.md`

> **SUPERSEDED.** The "Liveness Shrink" mechanism proposed below was never built.
> No part of it exists in the source tree: `grep -rln "LivenessShrink" modules/`
> returns no source file, and the symbol `LivenessShrink` (the vote, the certificate,
> `checkLivenessShrinkAssembly`, the `eligiblePeers: Map[AppEnvironment, Set[PeerId]]`
> config, the gossip routing) appears in no Scala source. It is referenced only in this
> doc and in the shipped mechanism's doc (`quorum-shrink.md`, which records it as the
> superseded predecessor).
>
> The wedge class it targets (a quorum-threshold subset of the locked-in committee
> persistently down, exemplified by the 2026-05-11/05-12 rollback at ordinal 3122961
> and wedge at round 3122962) was instead solved by **QuorumDenominatorShrink**
> (`state/QuorumDenominatorShrink.scala`, commit `f22132d69`, consensusSchemaVersion 33).
> See **[quorum-shrink.md](quorum-shrink.md)** for the shipped design.
>
> The shipped mechanism makes a structurally different trade than this proposal:
> - It introduces **no permissioned eligible-peer set** and **no new vote or
>   certificate wire type**. The shrink is a pure function of consensus-agreed
>   inputs plus a local wall clock, re-derived at every consumer.
> - It **does not shrink the committee**: `roundStartFacilitators` and
>   `facilitatorsHash` are left byte-identical (`QuorumDenominatorShrink.scala:21-23`).
>   It lowers only the quorum **denominator** used for phase/cert feasibility at the
>   stuck key, so the same committee re-runs the round at a reduced required threshold.
> - It is gated per environment by `quorum-shrink-activation-views` (testnet only;
>   mainnet and dev deliberately leave it off).
>
> Do not follow the Implementation plan below: building it would add a duplicate,
> conflicting mechanism. The body is preserved only for design-history context.

## Problem

When a cluster wedges because a quorum-threshold subset of the locked-in facilitator set is persistently down (not transiently silent, not Byzantine - *operationally absent*), the BFT eviction-cert path cannot make progress. Eviction quorum is itself committee-sized - to evict, you need the same supermajority you need for any round to close. With committee `n=8`, threshold `0.67`, you need 6 votes to evict; if 5 facilitators are absent and 3 are active, you have 3 voters and can never reach 6. The cluster is provably stuck until enough absent peers come back, even though 3 honest operator-controlled source nodes are willing and able to continue.

This is the failure mode of testnet 2026-05-11 -> 2026-05-12 (rollback at ordinal 3122961, wedge at round 3122962, 13+ hours stuck). The shape recurs whenever an event correlates absence across >`f` committee members at the same key: a network partition that severs community peers from the cluster, a misconfiguration that takes down an operator's stake of nodes, a coordinated restart cascade after a chain-wide deploy.

## Threat model and design lens

Tessellation's networks are permissioned. The seedlist gates participation; collateral gates eligibility; node operators are accountable identities, not anonymous adversaries. The dominant failure mode is *flaky byzantine* - peers that crash, partition, or stall - not *adversarial byzantine* - peers that actively lie or collude. This is the same distinction Hyperledger Fabric makes when it allows a predefined consortium ("channel admins") to issue channel-reconfiguration transactions that change the orderer set without a full BFT vote: the consortium itself is the trust anchor.

We can make the same trade in Tessellation, scoped to environments and peer sets we explicitly designate. **Mainnet does not opt in**; in Mainnet the BFT envelope is preserved unchanged. **Testnet/Integrationnet opt in** with an explicit, operator-curated list of peer IDs who can collectively decide to shrink a wedged committee.

The trade is real and we should be honest about it:

- **We surrender**: cryptographic safety against a Byzantine majority within the eligible set. If the eligible peers collude (or are simultaneously compromised), they can evict honest committee members and continue the chain on their own.
- **We retain**: safety against any peer NOT in the eligible set, safety against the eligible set partitioning evenly (with `|eligible|` odd, no even split exists), and full BFT for round-close itself (the shrink only changes membership; the resulting smaller committee still uses supermajority).
- **We gain**: liveness in the exact scenario that has cost the most operator pain - known operators ready to continue while the wedged committee waits on permanently-absent peers.

## Protocol design

### Configuration

```scala
case class LivenessShrinkConfig(
  eligiblePeers: Map[AppEnvironment, Set[PeerId]],
  abandonmentThreshold: NonNegInt,    // e.g. 10
  elapsedThreshold: FiniteDuration    // e.g. 10.minutes
)
```

Per-environment defaults:

| Environment      | `eligiblePeers`                                     |
|------------------|-----------------------------------------------------|
| `Mainnet`        | `Set.empty`                                         |
| `Integrationnet` | Curated source-node peer IDs                        |
| `Testnet`        | Curated source-node peer IDs (.79, .45, .193, ...)    |
| `Dev`            | `Set.empty` (local consensus uses other mechanisms) |

Empty set = mechanism completely off. No code path can issue a shrink without a non-empty eligible set.

### Trigger conditions

`AbandonmentTracker` evaluates after each retriable abandonment at the same key. **All** must hold:

1. `retriableAtSameKey >= abandonmentThreshold` - we've tried repeatedly at this key
2. `elapsed >= elapsedThreshold` since first abandonment at this key - sustained, not transient
3. `peersAtHigherKey == 0` - we're at the cluster tip; not falling behind
4. `selfId in  eligiblePeers(env)` - self is allowed to issue
5. `activeEligible.size >= majority(eligiblePeers(env))` - enough eligible peers are responsive to form quorum, where `majority = floor(|eligible|/2) + 1`
6. `roundStartFacilitators intersect eligiblePeers(env) >= 1` - at least one eligible peer is in the wedged committee (sanity check; without this the shrink doesn't help)

### Vote message

```scala
case class LivenessShrinkVote(
  key: Key,
  facilitatorsHash: Hash,        // hash of the wedged committee (roundStartFacilitators)
  evictedSet: SortedSet[PeerId], // committee members not in activeEligible
  newCommittee: SortedSet[PeerId], // the shrunken committee
  reason: ShrinkReason,           // QUORUM_INFEASIBLE (only variant initially)
  observedAt: SnapshotOrdinal
)
```

When trigger fires, self computes:
- `evictedSet = roundStartFacilitators \ activeEligible` (committee members who are not currently responsive eligible peers)
- `newCommittee = roundStartFacilitators \ evictedSet` (whatever's left)

It signs and gossips `Signed[LivenessShrinkVote]`. The shrink is bound to a specific committee (`facilitatorsHash`) so a vote cast against committee A cannot be replayed against committee B.

### Assembly and quorum

In `StateTransitions`, mirror `checkEvictionAssembly`:

```scala
def checkLivenessShrinkAssembly(key: Key): F[Unit] = ...
  val n = eligiblePeers(env).size
  val q = math.max(1, n / 2 + 1)  // majority of eligible, NOT supermajority of committee
  val votes = resources.livenessShrinkVotes.getOrElse(key, Map.empty)
  val matching = votes.values.filter(v => v.signer in  eligiblePeers(env))
                              .groupBy(v => (v.facilitatorsHash, v.evictedSet, v.newCommittee))
                              .find { case (_, vs) => vs.size >= q }
  matching.foreach { case ((facHash, evicted, newComm), agreedVotes) =>
    // Build LivenessShrinkCertificate from agreedVotes
    // Persist; emit ApplyLivenessShrink command
  }
```

Quorum denominator is `|eligiblePeers(env)|`, **not** `|roundStartFacilitators|`. This is the key safety relaxation. For 3 source nodes: quorum = 2.

### Certificate

```scala
case class LivenessShrinkCertificate(
  key: Key,
  facilitatorsHash: Hash,
  evictedSet: SortedSet[PeerId],
  newCommittee: SortedSet[PeerId],
  reason: ShrinkReason,
  signatures: NonEmptySet[SignatureProof] // >= majority of eligiblePeers
)
```

### Application

When `ApplyLivenessShrink(cert)` fires (either locally after assembly, or via gossip received from an assembler):

1. Verify `cert.signatures.size >= majority(eligiblePeers(env))`
2. Verify every signer is in `eligiblePeers(env)`
3. Verify `cert.facilitatorsHash` matches local `roundStartFacilitators`
4. Verify `cert.evictedSet union cert.newCommittee == roundStartFacilitators` (no spurious additions)
5. Update local state:
   - `roundStartFacilitators := cert.newCommittee`
   - `state.facilitators := cert.newCommittee`
   - Recompute and persist `facilitatorsHash`
   - Persist `cert` in consensus storage (so restarts pick up the new committee)
6. Re-queue `StartRound` with the new committee

Non-eligible peers verify (1)-(4) and apply (5)-(6). They do not sign anything; the cert is self-authenticating via `cert.signatures`.

### Network

Add gossip routing:
- `LivenessShrinkVote` -> relayed peer-to-peer like `EvictionVote`
- `LivenessShrinkCertificate` -> relayed peer-to-peer like `EvictionCertificate`

No new HTTP routes needed; reuse the existing gossip plumbing.

### Recovery and re-admission

Evicted peers eventually recover (network heals, hardware comes back, operator restarts). When they reach `Ready`, they go through the standard B2 admission flow:
- `AdmissionVote` from active committee members
- `AdmissionCertificate` assembled at supermajority quorum
- Cert applied at next ordinal, peer rejoins committee

The Liveness Shrink does NOT modify the eligible set itself - that's static, operator-curated config. It only modifies the per-round committee.

## Safety analysis

### What an attacker on the eligible set could do

An attacker controlling `>= majority(eligiblePeers)` could:

1. **Evict honest committee members repeatedly** to keep the chain in a state they prefer. Mitigation: eligible set is operator-curated; this requires colluding operators, which is the trust assumption.
2. **Stall the chain via never-issuing the shrink**. Same as today - they can already do this by not voting. Not a regression.
3. **Replay a stale `LivenessShrinkVote`**. Mitigation: each vote binds to `(key, facilitatorsHash)`. A vote for committee A at key K cannot be applied to committee B or at key J != K.

### What an attacker NOT on the eligible set could do

Nothing different from today. They cannot sign valid `LivenessShrinkVote`s - non-eligible signatures are rejected at assembly time and at cert verification.

### Partition scenarios

**Clean split of eligible peers**: Impossible if `|eligible|` is odd. With 3 eligibles, any partition gives 1+2 or 0+3 - only the 2-side can shrink. The 1-side cannot reach `majority=2` and waits.

**Asymmetric reachability**: Eligible A can reach B and C, but B cannot reach C. A is the only one who can collect a 2-vote quorum and assemble a cert. A's cert is then broadcast; B and C apply it. Determinism: A picks `evictedSet` based on its view of `activeEligible`. If A's `activeEligible = {A, B, C}` it shrinks to itself effectively, which may not be what we want, but is *consistent* across the cluster.

**Mainnet safety**: `eligiblePeers(Mainnet) = Set.empty`. Quorum `q = max(1, 0/2 + 1) = 1`, but `votes.values.filter(v => v.signer in  Set.empty)` is always empty, so `agreedVotes.size >= 1` never holds. No shrink can be assembled. Operationally inert.

### Determinism

The cert is the determinism anchor: every node that sees the cert applies the same committee transformation. Unlike eviction-cert-deterministic-shrinkage's gap (cert assembled but never applied at the wedged ordinal), Liveness Shrink applies the cert *immediately* at the wedged key - the round is then restarted from scratch with the new committee. No proposal-acceptance window required.

### Comparison with B1 eviction

> **Correction (post-v19).** The B1 column below described an earlier code state.
> Current B1 eviction-assembly computes its quorum denominator from the **Core**
> committee, not `roundStartFacilitators`: `val n = state.coreFacilitators.value.size`
> then `q = max(1, QuorumPolicy.fromFraction(n, config.quorumThresholdFraction))`
> (`StateTransitions.scala:1012-1019`, the "v19: ECS assembly quorum threshold computed
> against the Core committee" change). The signer/witness pool is widened separately
> (the eligible-facilitator union), but that widening is orthogonal to the denominator,
> which is Core-sized.

| Property                | B1 Eviction              | Liveness Shrink           |
|-------------------------|--------------------------|---------------------------|
| Quorum denominator      | `coreFacilitators` (Core-sized, v19) | `eligiblePeers(env)` (m) |
| Quorum threshold        | `0.67` supermajority     | `0.5+1` simple majority   |
| Who can sign            | Any committee member     | Only `eligiblePeers(env)` |
| Per-target cap          | Yes (n - quorum)         | No (single bulk eviction) |
| Applies at              | Next ordinal's proposal-accept | Same key, immediately |
| Mainnet enabled         | Yes                      | No (empty eligible set)   |
| Recovers committee from | Per-target cert applied  | Bulk cert applied         |

## Liveness analysis

### Testnet 2026-05-12 wedge

Committee `n=8`, locked-in: 3 source + 5 community. Community persistently down.

Without Liveness Shrink: deadlock. 3 voters < 6 quorum, 3 voters < 6 eviction quorum.

With Liveness Shrink (`eligiblePeers = {.79, .45, .193}`, majority = 2):
1. `abandonmentThreshold` (10) and `elapsedThreshold` (10 min) trip after the cluster has been wedged for ~10 min
2. Each source node emits `LivenessShrinkVote(key=3122962, evictedSet={401e1863, 822cf3a2, 90eb1ed3, 9561959b, b79d9817}, newCommittee={.79, .45, .193})`
3. Any source node observes 2 matching votes (own + one peer's) -> assembles cert
4. Cert applied locally and broadcast -> other sources receive and apply
5. Round 3122962 restarts with `roundStartFacilitators = {.79, .45, .193}`, quorum = 2
6. Round closes with 3-of-3 facilitators
7. Cluster advances. Community peers recover and rejoin via B2 admission over subsequent rounds.

Expected wall-clock time from trigger to cluster advancement: ~10 minutes (trigger window) + ~30s (vote propagation + cert assembly + round restart) approximately  11 minutes.

### Pathological case: lone eligible peer responsive

If only 1 of 3 source nodes is up, `activeEligible.size = 1 < majority=2`. Trigger condition (5) fails. No shrink possible. This is correct: a single peer cannot unilaterally shrink the cluster; it would need to coordinate with at least one other eligible. If only 1 eligible is reachable, the cluster's safety story has bigger issues than a wedged committee.

## Implementation plan

| Phase | Scope                                            | Files touched                                                | Effort |
|-------|--------------------------------------------------|--------------------------------------------------------------|--------|
| 1     | Config types, per-env defaults                   | `node-shared/config/types.scala`, `defaults.scala`           | 30 min |
| 2     | `LivenessShrinkVote`, `LivenessShrinkCertificate`, schema | `consensus/message/*`, `schema/consensus/*`         | 1 hr   |
| 3     | Storage: `livenessShrinkVotes`, `assembledLivenessShrinkCerts` | `consensus/storage/ConsensusStorage.scala`     | 1 hr   |
| 4     | Trigger detection in `AbandonmentTracker`        | `consensus/engine/AbandonmentTracker.scala`                  | 1 hr   |
| 5     | Assembly + cert application in `StateTransitions`| `consensus/state/StateTransitions.scala`                     | 2 hrs  |
| 6     | Gossip routing                                   | `infrastructure/gossip/*`                                    | 30 min |
| 7     | Tests: unit + property                           | `node-shared/test/**`                                        | 2 hrs  |
| 8     | E2E test: simulate wedge + recovery              | `dag-l0/test/**` or new E2E scenario                         | 2 hrs  |

Total: ~10 hours focused dev + Codex review + manual testnet validation.

## Alternatives considered

### Lower eviction-cert quorum to `(n-cap) x fraction`

Suggested by the fork-recovery E2E debug agent. For `n=8`: quorum drops from 6 to 5. Helps small-committee 1-2-silent cases, **does not help** our wedge (still needs 5 voters, we have 3). Safety surrender is symmetric to BFT loosening but smaller. Could be shipped independently as a minor polish to eviction.

### Per-env BFT/Permissioned config knob

`consensus.byzantineFaultModel: BFT | Permissioned`. In `Permissioned`, all quorums drop to `floor(n/2)+1`. Helps general liveness tolerance but doesn't solve the specific wedge of `f` simultaneously absent peers when threshold is already exhausted. Also a broader change with more sites to audit.

### Manual operator override

CLI command on a designated peer that issues a `ForceFacilitatorReset` for the cluster. Simpler to implement but requires manual operator action every wedge. Liveness Shrink automates the same outcome with operator-curated trust as the source.

### Continue waiting for absent peers

Status quo. Acceptable if absent peers are guaranteed to come back within a tolerable window. Not acceptable as observed in May 2026 with 13+ hour wedges.

## Operational considerations

### Configuring `eligiblePeers`

- The eligible set should be a small, stable set of operator-controlled peers. 3-7 is a reasonable range.
- Adding/removing peers requires a coordinated config deploy across the cluster (the eligibility check is local to each peer, but determinism requires all nodes agree on who can sign).
- Mainnet should remain empty until and unless a deliberate governance decision is made.

### Detecting misuse

The cert is on the wire and persisted in consensus storage. Operators can audit: how often does shrink fire? Which eligible peers signed each cert? Anomalous patterns (frequent shrinks, single-peer concentration of signatures) are visible.

### Metrics

- `dag_consensus_liveness_shrink_triggered_total{env}`
- `dag_consensus_liveness_shrink_cert_assembled_total{env}`
- `dag_consensus_liveness_shrink_cert_applied_total{env}`
- `dag_consensus_liveness_shrink_evicted_total{env}` (size of evicted set per shrink)

## Open questions

1. **Should the eligible set include WaitingForReady peers as "active" for trigger condition 5, or only Ready?** Currently the spec says `activeEligible` is responsive eligible peers - should match the existing `ResponsivePeers` definition + state filter. Lean: include WFR (matches the alpha.63 widening).
2. **Cool-down between shrinks at the same key?** If a shrink fires but the new committee STILL can't close (another absent peer in `newCommittee`), should we shrink again or hold? Lean: hold for N rounds to let the smaller committee try.
3. **Should the cert include a timestamp for replay protection?** It binds to `(key, facilitatorsHash)` which is replay-safe - but a malicious assembler could withhold a cert and replay it later if the same wedge recurs. Lean: not needed in v1.
4. **Interaction with the existing v18 abandon-gate?** v18 suppresses retry when wedged + no peer ahead. Liveness Shrink should fire *during* the suppression window, not be blocked by it. Verify the trigger evaluation point sits before any suppression early-exit.

## Recommendation

The mechanism is sound; the safety surrender is bounded and per-environment; the prior art (Hyperledger Fabric channel reconfiguration) is solid. Implementation is non-trivial but tractable.

The pacing decision is for the team: implement and deploy after the current testnet wedge resolves (so we're not changing consensus during recovery), or defer to a future cycle if Mainnet operator confidence isn't there yet. Either way, the design is ready to be reviewed and refined.
