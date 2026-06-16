# Deterministic eviction-cert-driven committee shrinkage

**Status:** SUPERSEDED / not adopted. Retained as historical context.
**Author:** ottobot-ai (with @scasplte2)
**Date:** 2026-05-07
**Related:** `quorum-shrink.md` (the shipped liveness remedy), PR #1485 (fork-recovery followup), PR #1476 (B1 eviction mechanism)

> **SUPERSEDED.** The recommended fix below (approach alpha: add
> `appliedEvictionCerts: List[EvictionCertificate]` to the `Facility` declaration and
> apply eviction certs at round-start) was implemented as v13 (commit `1dbc4c284`) and
> then **reverted** (commit `5b2ce6722`, "Revert ... deterministic same-ordinal committee
> shrinkage ... (v13)"). The current `Facility` case class (`declaration.scala:46-74`)
> carries `consensusConfigHash`, `selfHealthHint`, and `proposerClockMs`, but has no
> `appliedEvictionCerts` field; the symbol survives only as a stale v13 entry in the
> `consensusSchemaVersion` deploy-history comment at `config/types.scala:628`.
>
> Eviction certificates are still embedded in `Proposal.evictionCertificates`
> (`declaration.scala:428`) and applied only at **proposal-acceptance of the next
> ordinal**: on acceptance the accepted certs' targets are accumulated into
> `removedFacilitators` (`GlobalSnapshotConsensusStateAdvancer.scala:2902-2904`,
> applied into the round state at `:3029`; signature verification of the embedded certs
> is the separate `verifyEcsSignatures` at `:1881`). In other words the "mechanical gap"
> the Problem section describes below is still the design; the cert remains
> proposal-embedded and proposal-accept-applied.
>
> The liveness remedy for the wedge class this doc targets (ordinal 3121304) was instead
> delivered by **QuorumDenominatorShrink** (`state/QuorumDenominatorShrink.scala`, commit
> `f22132d69`, consensusSchemaVersion 33), which unwedges a stuck committee by lowering
> the quorum **denominator** at the stuck key rather than by reducing the committee. Note
> that `QuorumDenominatorShrink` is wired into the VCC/TimeoutCertificate view-advance
> quorums (`shrinkDecision.builderQuorum(...)` at `StateTransitions.scala:224`/`361`/`586`/`738`),
> not into the eviction-assembly quorum: `checkEvictionAssembly` still computes its
> threshold from the Core committee via `QuorumPolicy.fromFraction` (`StateTransitions.scala:1018-1019`).
> See **[quorum-shrink.md](quorum-shrink.md)**.
>
> The body below is preserved for design-history context. Note that several inline
> `declaration.scala` / `*StateAdvancer.scala` / `ConsensusStorage.scala` line anchors
> in it predate later refactors and no longer point at the cited code; treat them as
> approximate.

## Problem

When a cluster wedges at a single ordinal because too many facilitators are persistently unresponsive, the existing B1 eviction path can assemble an `EvictionCertificate` for the offending peer but cannot apply it. The cert is consumed only at proposal-acceptance time of the *next* ordinal — and at a wedged ordinal, no proposal is ever accepted. The cluster keeps re-collecting eviction-vote quorum on every retry without ever reducing the committee, and stays stuck.

### Evidence: testnet 2026-05-07, ordinal 3121304

dag-l0 testnet, 11-peer permissioned cluster, quorum threshold 0.67. Pulled from `.workspace/logs/testnet-20260507-132926/13-57-110-45/json_logs/app.json.log`:

| Time (UTC) | Event |
|---|---|
| 18:00:51 | Round 3121304 starts. Committee=9, leader=b79d9817. Note: previous ordinal 3121303 finalized with `lastSignerCount=3` — cluster was already heavily degraded entering 3121304. |
| 18:01:55 | First STALL_DETECTED: `phase=CollectingFacilities progress=3/9 evicted=6 remaining=3 minQuorum=7 quorumFeasible=false`. B1 eviction votes begin to flow against 9d101813 and others. |
| 18:01:57 | Eviction-vote tally for 9d101813 reaches **3/7 quorum** then stalls (only 8 distinct voters in the cluster; multiple targets compete for the same vote pool). |
| 18:02:26 | Round abandoned (`max_stalls`). |
| 18:07:52 | After 5 consecutive abandonments at 3121304, `recoverySuppressed=true` activates correctly (`peersAtHigherKey=0`, cluster-wide stall — no point cascading WFD). |
| 18:13:20 | A retry-round actually progresses Facilities → Proposals → Signatures (proposal artifact built, leader = b79d9817, view=0). Round still abandons in Signatures phase: `progress=5/9 remaining=5 minQuorum=7 quorumFeasible=false`. |
| 18:21:22 | View change to view=1, leader=9d101813 — *the new leader is one of the unresponsive peers*. |
| 18:23:33 | **Eviction quorum=7 reached for 9d101813** (`assembly=quorum_reached_cert_stored`). Cert assembled, stored locally, but no proposal at 3121304 is ever accepted, so the cert is never applied. |
| 18:28:07 | Same eviction quorum reached **again** in a later retry-round (votes re-collected from scratch). |
| 18:29:43 | Logs end. Cluster still on 3121304 (29 minutes wedged, no progress past this ordinal). |

**Observed counts on this single node at 3121304:**

- 16 ROUND_FACILITATING events (16 retry attempts)
- 30 ROUND_ABANDONED (15 events on this layer)
- 33 STALL_DETECTED with `reason=QUORUM_INFEASIBLE_EVICTION`
- 26 EVICTION events (B1 eviction votes flowing)
- Persistent missing peers in `unresponsiveMissing`: 9d101813 (15 occurrences), 401e1863 (15), 32b7be18 (some), 03313ea1 (some)

**The mechanical gap:** `clearResourcesPreservingDeclarations` already preserves `assembledEvictionCerts` across abandonment retries (see `ConsensusStorage.scala:592` for the existing comment). But the only readers of `getAssembledEvictionCertificates(key)` are `GlobalSnapshotConsensusStateAdvancer.scala:822, 926` and `CurrencySnapshotConsensusStateAdvancer.scala:535, 621` — both at proposal-build time. Round-start facilitator selection in `*StateCreator.scala` ignores assembled certs entirely. So the cert exists in storage but never reduces the committee for the next retry-round.

## Why the existing design is correct (and the boundary we must respect)

The existing design intentionally applies the cert at *proposal acceptance* of the next ordinal because that is the only point at which all honest nodes can be guaranteed to see the cert — it's embedded in a Proposal that quorum-many peers signed. This is fundamentally a determinism guarantee: every facilitator with the cert at `lastOutcome` derives the same committee at the next ordinal.

Any change that applies the cert *earlier* must preserve this determinism invariant or it forks the cluster. From the user's review:

> we have had lots of trouble with local view changes leading to non-deterministic views across the network due to latency

This is the lens through which the design must be evaluated. **Faster is not better if it sacrifices determinism.**

## Determinism failure mode of the naive fix

The naive fix would be: at round-start in `*StateCreator`, call `getAssembledEvictionCertificates(key)` and exclude target peers from the eligible set. This is straightforward to implement (~30 LOC per layer) but fails determinism:

```
Time T0: Node A assembles cert C for target X. Stores in local assembledEvictionCertsR.
Time T0+ε: Round at key K abandons.
Time T0+ε+δ: Both A and B start retry-round. A has C, B does not yet (gossip-RTT latency).
  - A computes committee = eligible \ {X}.  hash_A = hash(committee_A).
  - B computes committee = eligible.        hash_B = hash(committee_B).
  - hash_A ≠ hash_B.
  - A's Facility carries facilitatorsHash = hash_A.
  - B's Facility carries facilitatorsHash = hash_B.
  - They're now signing against different committees and cannot reconcile until BOTH have C.
```

Even if A gossips C to B at T0, gossip delivery is best-effort — B may receive it after starting its retry-round. The retry now wedges on hash mismatch in addition to the original wedge.

A node-RTT-bounded transient is a real degradation: in a 12-peer cluster, even one slow link means three or four nodes see the cert before the rest, and during that window quorum on either hash variant alone is at most 7-of-12 — exactly minQuorum, and only if the timing is favorable. Multiple stuck retries can occur before convergence.

## Design alternatives

### α — Embed certs in the Facility declaration

Each node's Facility declaration includes a `List[EvictionCertificate]` field listing certs the sender is applying. The sender's `facilitatorsHash` is computed from the post-exclusion committee.

On receipt, the receiver:
1. Validates each embedded cert structurally (re-runs `EvictionCertificateBuilder.build`).
2. Locally computes the committee assuming those certs apply.
3. Verifies the sender's `facilitatorsHash` matches.

When quorum-many Facilities embed the *same* cert C and agree on a `facilitatorsHash`, that's quorum-witnessed agreement to apply C at this round. Receivers without C originally now apply C locally (because they have proof that quorum-many peers are applying it).

This is the cleanest design. It mirrors how `viewChangeCertificate` and `evictionCertificates` are already embedded in `Proposal` (declaration.scala:304); it just moves the embedding earlier in the round.

**Pros:**
- No "race" window: a Facility that applies C is self-describing — the cert is in the message.
- Deterministic activation: only when quorum-many Facilities agree on (committee, certs) does the round advance. If not enough peers have C, the round abandons and another retry happens.
- Activation is gated by the same quorum that already governs all consensus decisions.
- No new wire-format primitive beyond the cert itself (which already exists).

**Cons:**
- Schema change to `Facility` case class (declaration.scala:34-42). Backward-compat handling needed: old peers won't include the field, new peers must accept defaulted-empty.
- Facility size grows linearly in the number of embedded certs. Bounded by committee size (typically ≤ N evictions per round).
- During the convergence window (some peers have C, others don't), the round may abandon once or twice before quorum-many peers all have C. This is bounded by gossip-RTT × abandonment-cycle and is a transient liveness cost, not a safety issue.

### β — Cert-via-deterministic-countdown after gossip propagation

Gossip the cert on assembly. Each peer counts how many distinct peers have referenced the cert (e.g., echoed it back via a follow-up gossip message). When `≥ k` peers have the cert (where k is some threshold like minQuorum), all peers locally activate the cert at the next round-start.

**Pros:**
- Fully out-of-band — no Facility schema change.

**Cons:**
- Activation criterion is fuzzy (k ack-receipts vs quorum). Likely to drift.
- Adds two new gossip messages (cert + ack) instead of one Facility field.
- Activation timing is asynchronous — peers may activate at different rounds.
- Determinism reasoning relies on "everyone has the cert before activation" which is a probabilistic property without a hard quorum gate.

### γ — Cluster-wide config flag + epoch-based activation

Application of cert-driven shrinkage is gated by a `deterministicConfigHash`-bound flag. Operator flips it when the cluster has fully deployed; activation happens at a deterministic ordinal boundary.

**Pros:**
- Simplest determinism argument: flag is consensus-agreed.

**Cons:**
- Doesn't actually help during the rolling-deploy window — operator must wait until 100% of the cluster has the binary, which is often when the bug bites worst.
- Doesn't solve the core problem (cert-applied-at-same-ordinal); only enables a future fix.

### δ — Status quo (do nothing)

The cluster stays wedged when this scenario occurs. Operator restarts a peer to break the wedge.

**Pros:**
- Zero risk.

**Cons:**
- Wedging happens in production. The 2026-05-07 testnet incident wedged for 29+ minutes.
- Mitigations (operator alerts, manual restart) are reactive.

## Recommended approach: α with explicit determinism gate

Embed certs in Facility declarations. The activation criterion is the same quorum gate that already governs all consensus decisions: **a cert is applied at round N iff quorum-many Facilities at round N embed that cert and agree on the resulting `facilitatorsHash`.**

### Determinism analysis

**Claim:** If two honest nodes A and B finalize round N with `facilitatorsHash = h`, they have identical committee sets and identical applied-cert sets.

**Proof sketch:**
1. Both A and B accepted (q-many) Facilities at round N with `facilitatorsHash = h`.
2. Each accepted Facility must encode the same `(committee, applied_certs)` tuple to produce hash `h` (collision-resistant hash).
3. Therefore A and B agree on which certs were applied at round N.

**Claim:** A node activates a cert at round N only when quorum-many honest nodes also activate it.

**Proof sketch:**
1. A node accepts a Facility with cert C only if the cert is structurally valid (`EvictionCertificateBuilder.build` succeeds).
2. The node's own Facility for round N includes C only if the node has seen quorum-many other Facilities for round N include C.
3. Therefore application of C at round N requires quorum-many peers to have already committed to applying C.

**Edge case:** the FIRST node to apply C ever — it has the cert (assembled it itself or received via gossip) but no Facility evidence yet. That node's Facility must reflect its decision. So the activation criterion must be:
- "A node may apply cert C in its Facility for round N if it has C in local storage" (sufficient condition for the first node).
- "A node receiving Facilities from peers updates its applied-certs view based on quorum-witnessed agreement" (subsequent nodes).

The convergence dynamic: a node assembling C asserts C in its Facility. Other nodes either also have C (assemble it independently from same vote pool) and assert it in their Facilities, OR they don't yet, and their Facilities don't apply C. The hash mismatch causes the round to abandon if no `(certs, hash)` tuple reaches quorum. Gossip catches the laggards up before the next retry. Eventually quorum-many Facilities agree on `(certs={C}, hash=h)` and the round proceeds.

**This bounds the cost: a transient ~1-2 retry windows of liveness latency in exchange for deterministic activation.** That's acceptable given the alternative is indefinite wedging.

### Implementation outline (PR2)

PR1 (gossip carrier — already partially scaffolded) becomes the prerequisite that gets the cert to all nodes faster, reducing the convergence window.

PR2:

1. **Schema change to Facility:**
   ```scala
   case class Facility(
     eventHashes: Set[Hash],
     candidates: Candidates,
     trigger: Option[ConsensusTrigger],
     facilitatorsHash: Hash,
     lastGlobalSnapshotOrdinal: SnapshotOrdinal,
     lastSnapshotHash: Hash,
     consensusConfigHash: Option[Hash] = None,
     appliedEvictionCerts: List[EvictionCertificate] = List.empty  // NEW
   ) extends PeerDeclaration
   ```
   Default empty for backward compat (older nodes treat as absent → empty).

2. **`*StateCreator` (dag-l0 + currency-l0):**
   - At round-start, read `consensusStorage.getAssembledEvictionCertificates(key).map(_.toList)` to compute the local-applied-certs set.
   - Pass through to the Facility declaration when the node is a facilitator.
   - Compute `facilitatorsHash` from the post-exclusion committee.

3. **Facility receive path (`storage.addFacility` + `ConsensusStateUpdater.tryUpdateConsensus`):**
   - Validate each embedded cert via `EvictionCertificateBuilder.build` (mirror the validation path used in proposal acceptance).
   - Reject the Facility if any embedded cert fails validation.
   - Group received Facilities by `(applied_certs_set, facilitatorsHash)`. The first group reaching quorum is the consensus committee for this round.

4. **Quorum agreement on committee:**
   - This is already what `tryUpdateConsensus` does — count Facilities by `facilitatorsHash` and advance to Proposal phase when one tuple reaches quorum.
   - The new logic is additive: just include `applied_certs_set` in the grouping key implicitly via `facilitatorsHash` (since hash is computed from the post-exclusion committee).

5. **`consensusConfigHash` gate:**
   - The Facility schema change is included in `deterministicConfigHash` so old/new peers cannot mix-match within the same round.
   - During rolling deploy, peers running different versions effectively sign different config hashes and partition cleanly until all are upgraded.

6. **Tests:**
   - Unit: cert-applied-by-self → Facility includes cert → hash reflects shrunk committee.
   - Unit: receive Facility with embedded cert → validate + apply locally → match.
   - Unit: receive Facility with malformed cert → reject.
   - Property: deterministic hash given identical (committee, certs) input across nodes.
   - E2E: existing fork-recovery harness scenario reproducing 3121304 — induce one peer to drop facility declarations, assert cluster recovers within 2-3 retry cycles instead of indefinitely.

### Rolling-deploy concerns

The Facility schema change requires `consensusConfigHash` bumping. This means:
- Mixed old/new peers cannot reach Facility quorum during rolling deploy.
- New peers must include the cert field but produce hashes compatible with old peers when no certs apply.

The default-empty-list approach gives compatibility when no eviction is happening:
- New peer sends Facility with `appliedEvictionCerts = []` and `facilitatorsHash = hash(full_committee)`.
- Old peer sends Facility without the field (decoded as absent → default empty) and `facilitatorsHash = hash(full_committee)`.
- Hashes match → consensus proceeds.

When eviction happens:
- New peer sends Facility with `appliedEvictionCerts = [C]` and `facilitatorsHash = hash(committee \ {target_of_C})`.
- Old peer sends Facility with `facilitatorsHash = hash(full_committee)`.
- Hashes mismatch → round abandons until `consensusConfigHash` bumps.

So during rolling deploy, the new behavior is dormant unless eviction fires. If eviction fires during the deploy window, there's a brief partition until the cluster fully upgrades. This is acceptable for a planned operation.

### Open questions

1. **Should application of a cert at round N be unconditional once a node has the cert, or gated on observing quorum-many Facilities reference it?** The unconditional approach is simpler but produces a longer convergence transient. The gated approach is more conservative but requires the node to track which certs are "blessed" vs "owned".

2. **What about cert assembly DURING a round (mid-Facilities, mid-Proposal)?** The current StallDetector emits eviction votes mid-Facilities. If a cert assembles mid-round, should we abandon-and-restart with the cert applied, or wait for the next abandon cycle? Recommend: wait for next abandon — the round is already in progress; switching mid-round adds complexity for marginal gain.

3. **What's the rolling-deploy plan?** Recommend: ship PR1 first (gossip-carrier infrastructure, no behavioral change). Verify cluster-wide deployment via metric (`consensus_eviction_cert_gossip_received_total`). Then ship PR2 with `consensusConfigHash` bump.

4. **Interaction with B2 admission certs?** Admission and eviction are symmetric. The same design probably applies to admission certs but is out of scope for this proposal.

5. **Is there a cheaper fix that doesn't need a Facility schema change?** Path γ (cluster-wide config flag) might suffice if we accept slower activation. Worth benchmarking the gossip-RTT vs config-bump tradeoffs.

## Decision

Pending review by @scasplte2 and @ryle-ai. Recommend approval of approach α conditional on a satisfactory answer to open questions 1 and 5.
