# Quorum-denominator shrink (v33 liveness rung)

**Status:** Implemented and live (consensusSchemaVersion 33). Enabled on testnet
via `quorum-shrink-activation-views`; disabled on mainnet and dev.
**Related:** `README.md` (sections 5, 10, 11, 15), `eviction-cert-deterministic-shrinkage.md`,
`liveness-shrink-permissioned-fallback.md` (a superseded alternative; see
[Relationship to the permissioned-fallback proposal](#relationship-to-the-permissioned-fallback-proposal)).

`QuorumDenominatorShrink` is the structural liveness rung that unwedges a
"mathematically wedged" committee: a committee whose members are gossip-responsive
but consensus-dead, where every certificate family needs the same unreachable
supermajority and no round can ever close. It does this by deterministically
lowering the quorum **denominator** (the number of signers required to advance a
phase or assemble a certificate) at the stuck key after a wall-clock-anchored
period of silence, while leaving the persisted committee, `roundStartFacilitators`,
and `facilitatorsHash` byte-identical. It introduces no new wire types: the shrink
is a pure function of consensus-agreed inputs plus the local wall clock, re-derived
at every consumer, and is inert (every gate is byte-identical to pre-rung behavior)
when the rung is disabled or escalation has not begun. The source of truth is
`state/QuorumDenominatorShrink.scala`.

---

## The wedge it targets

A post-restart committee is seeded from the last snapshot, for example 6 members.
Some members are gossip-responsive but consensus-dead (divergent state, facilities
rejected, silent leader). Quorum is `supermajority(coreSize)`, 4-of-6. Every
certificate family -- the Facility / Proposal / Signature phase quorums, view-change
certificates (VCC), and timeout certificates (TC) -- requires that same 4-of-6, so
the healthy minority can neither rotate the leader, advance views, nor close a round.
No round closes, so no new `controllerEvidence` is recorded, so the evidence-driven
chronic-replacement ladder that would rebuild the committee around the healthy peers
never gets the miss evidence it needs. The cluster is wedged by construction
(`QuorumDenominatorShrink.scala:11-18`).

This rung lets the healthy subset close ONE round at a reduced quorum requirement.
Once that round closes the chain advances, the evidence window records the dead
peers' misses, and the normal chronic-replacement machinery takes over from the next
round-start derivation. The shrink applies only to the quorum denominator used for
cert/phase feasibility at the stuck key; the committee itself is never touched
(`QuorumDenominatorShrink.scala:20-23`).

What it shrinks is therefore the denominator, not the committee and not a
permissioned eligible-peer set. There is no "active facilitator set" mutation, no
vote to remove a peer, and no operator-curated allow-list.

---

## The determinism contract

Committee/quorum decisions derived from local observations (gossip responsiveness,
locally-seen declarations, local retry counters) diverge across nodes and have
repeatedly forked `facilitatorsHash`. Every input to this rung is therefore either
consensus-agreed data or a monotone shared anchor (`QuorumDenominatorShrink.scala:25-55`).

### The anchor set

`anchor` is the most recent `controllerEvidence` entry's `completedSigners`
intersected with `roundStartFacilitators` (`QuorumDenominatorShrink.scala:138-143`):

```scala
def anchor(
  latestEvidenceSigners: Option[SortedSet[PeerId]],
  roundStartFacilitators: Set[PeerId]
): SortedSet[PeerId] =
  latestEvidenceSigners.fold(SortedSet.empty[PeerId])(_.filter(roundStartFacilitators.contains))
```

`completedSigners` is canonical signed-outcome data (proposal-carried responders
via `ControllerEvidenceDerivation.canonicalCompletedSigners`, never locally-accreting
proofs); `roundStartFacilitators` is the frozen round committee, and every
Facility/MajoritySignature binds `facilitatorsHash`, so a node holding a different
committee cannot participate in the round at all. Two honest nodes that hold
byte-identical signed `lastOutcome` and byte-identical frozen committees compute
identical anchors. The rung deliberately does NOT anchor on the round's accepted
Facility set, which accretes in node-local arrival order.

### Escalation steps (wall-clock anchored)

`escalationSteps` measures wall-clock progress since the parent outcome's
`consensusEndTime` (the last entry of `lastOutcome.recentRoundEndTimes`), in
`viewInterval` units, via `ViewFromTime.compute`, minus the activation threshold
(`QuorumDenominatorShrink.scala:145-156`):

```scala
def escalationSteps(
  nowMs: Long,
  parentEndTimeMs: Option[Long],
  viewIntervalMs: Long,
  activationViews: Int
): Int =
  if (activationViews <= 0) 0
  else math.max(0, ViewFromTime.compute(nowMs, parentEndTimeMs, viewIntervalMs) - activationViews)
```

The parent end time is facility-median outcome data shared by all nodes that
finalized the parent round. The local wall clock is the only local input; it is
monotone, so two honest nodes disagree on `steps` only transiently and
one-directionally near a step boundary -- the stricter node accepts strictly LATER,
never a DIFFERENT value, and every consumer re-evaluates the gate periodically so a
transient split self-heals within the clock-skew bound. The result never feeds
`facilitatorsHash`, committee derivation, leader selection, or signed bytes.

This is deliberately a time anchor rather than a count of local abandonment cycles:
one abandonment cycle is roughly one `viewInterval` of silence, but the local
`AbandonmentTracker` counters are node-local `Ref`s that reset on restart and advance
at node-local cadence, so they must never seed consensus-critical state
(`QuorumDenominatorShrink.scala:52-55`).

### Config inputs

`quorumShrinkActivationViews`, `viewInterval`, and `quorumThresholdFraction` are all
folded into `deterministicConfigHash`, so divergent operator values handshake-reject
at Facility exchange rather than silently forking (`QuorumDenominatorShrink.scala:49-51`).

---

## Escalation and floor

With `q0 = max(1, QuorumPolicy.fromFraction(coreSize, fraction))` and `a = anchor.size`,
`requiredQuorum` is a two-stage function of `steps` (`QuorumDenominatorShrink.scala:162-172`):

- **Inert.** `steps <= 0` (silence shorter than `activationViews * viewInterval`, the
  rung disabled via `activationViews <= 0`, or no evidence/parent-end anchor):
  `required = q0`. Every gate is byte-identical to pre-rung behavior. An anchor smaller
  than `MinQuorumFloor` also disables shrinking, because a 1-peer anchor cannot certify
  anything meaningful.
- **Stage 1 (intersection-safe).** Each step lowers `required` by one, but not below the
  anchor majority `a / 2 + 1`. Two conflicting shrunken certs are impossible here:
  shrunken-margin votes must come from anchor members, and two disjoint anchor subsets
  of size `> a/2` cannot exist (`QuorumDenominatorShrink.scala:63-65`).
- **Stage 2 (liveness over partition safety, certificate paths only).** Below the anchor
  majority, one further reduction per `SubMajorityStepCost` steps, down to the hard floor
  `MinQuorumFloor`. This reduction can help liveness mechanisms such as VCC/TC leader
  rotation and B1/B2 certificate assembly continue to move under a deeply degraded Core.
  Post-bootstrap snapshot finalization does **not** follow this denominator all the way
  down: finality applies the frozen-round-committee floor, so a cluster-minority Core may
  rotate leaders or assemble liveness certificates but cannot finalize a divergent
  snapshot by itself.

Constants (`QuorumDenominatorShrink.scala:78-89`):

- `MinQuorumFloor = 2` -- the smallest count at which a certificate still represents
  agreement between distinct peers (a 1-vote cert would let any single node drive view
  changes).
- `SubMajorityStepCost = 5` -- escalation steps required per single sub-majority
  reduction; stage-2 reductions are deliberately 5x slower than stage-1 because they
  surrender the intersection guarantee.

For shrink/liveness decisions, `requiredQuorum` is clamped to
`[MinQuorumFloor, baseQuorum]`. For finality decisions, callers pass
`applyClusterFloor = true`, which additionally clamps the quorum to the
supermajority/unanimity floor of the frozen `roundStartFacilitators` committee outside
bootstrap.

---

## The Decision and its gate methods

`decide` (`QuorumDenominatorShrink.scala:177-199`) is the single derivation entry point
shared by every call site. It returns a pure `Decision` whose cross-node equality (for
nodes holding the same shared inputs) is the determinism contract. The `Decision`
exposes the gates that consumers use (`QuorumDenominatorShrink.scala:107-132`):

- `active` -- true only when escalation has begun AND the anchor is usable
  (`anchorSize >= MinQuorumFloor`) AND `requiredQuorum < baseQuorum`. When false every
  helper degrades to pre-rung arithmetic.
- `meets(voters)` -- the feasibility gate. Full base quorum always passes; the shrunken
  path requires `requiredQuorum` voters drawn FROM THE ANCHOR:

  ```scala
  def meets(voters: Set[PeerId]): Boolean =
    voters.size >= baseQuorum || (active && voters.count(anchor.contains) >= requiredQuorum)
  ```

- `shrunkPath(voters)` -- true when `meets` passes ONLY via the shrunken margin; the
  observability trigger for the rung-activation log line and counter.
- `builderQuorum(voters)` -- the quorum size handed to a certificate builder (which
  checks `signers.size >= quorumSize` internally): `baseQuorum` on the normal path,
  `requiredQuorum` on the shrunken path.
- `quorumOverride` -- `Some(requiredQuorum)` only while the rung is live; consumed by the
  `StallDetector` feasibility helpers.

Because the anchor is a subset of `roundStartFacilitators`, hence of every witness pool,
the shrunken margin never admits a voter that the existing builder/validator pool checks
would reject (`QuorumDenominatorShrink.scala:73-74`).

---

## How it wires into the round and acceptance path

There is a single shared derivation, `ConsensusStateAdvancer.quorumShrinkDecision`
(`state/ConsensusStateAdvancer.scala:99-113`), so the rung cannot drift between
consumers. It reads `state.coreFacilitators.value.size` as the core size,
`config.quorumThresholdFraction`, the layer-specific `latestEvidenceSigners` and
`lastOutcomeEndTimeMs` extractions (both default to `None`, leaving the rung inert for
layers that carry no evidence -- `ConsensusStateAdvancer.scala:83-92`),
`state.roundStartFacilitators`, the local `Clock`, `config.viewInterval`, and
`config.quorumShrinkActivationViews`.

The note in section 5 of the README that the advancer transitions at
`ceil(N * quorumThresholdFraction)` matching declarations still describes the base
quorum; this rung adjusts the requirement only at a stuck key after the activation
threshold.

The decision feeds these gates:

- **Phase quorums.** `maybeGetAllDeclarations` (the Facility / Proposal / Signature
  gate, `ConsensusStateAdvancer.scala:156-180`) derives the decision, then advances when
  `decision.meets(coreDeclared)` holds, where `coreDeclared` is the set of Core peers
  that declared. On the shrunken path it logs the `[QuorumShrink]` INFO line. Quorum is
  computed against the Core committee only; Tier-1 declarations are still collected and
  returned (so Tier-1 peers earn rewards) but cannot block or gate the phase.
- **VCC assembly and apply.** `checkViewChangeAssembly`
  (`state/StateTransitions.scala:213-225`) and `applyCertifiedViewChange`
  (`state/StateTransitions.scala:576-601`) each compute
  `q = shrinkDecision.builderQuorum(votes.keySet)`, gate on `shrinkDecision.meets(...)`,
  and pass `q` as the quorum size to `ViewChangeCertificateBuilder.build`. This is how
  `builderQuorum` feeds certificate-assembly quorum: the same escalated denominator that
  closes a phase also lets a stuck committee assemble the view-change certificate that
  rotates its leader.
- **Timeout-certificate assembly and apply.** `checkTimeoutCertificateAssembly`
  (`state/StateTransitions.scala:347-361`) and `applyCertifiedTimeoutCertificate`
  (`state/StateTransitions.scala:725-753`) do the same with the timeout votes, passing
  `q` to `TimeoutCertificateBuilder.build`. On the shrunken apply path `q` is also the
  certified-shrink floor, so the round-local active set may reduce to the TC voters.
- **Proposal-embedded cert validation.** `ProposalVccValidator` accepts an optional
  `QuorumDenominatorShrink.Decision` (`state/ProposalVccValidator.scala:69-90`) so a
  proposal-carried VCC/TC that closed under the shrunken denominator validates against
  the same derivation, NEVER against local retry counters.
- **B1 eviction assembly is not shrunk.** `checkEvictionAssembly`
  (`state/StateTransitions.scala:1009-1019`) computes its quorum directly as
  `q = max(1, QuorumPolicy.fromFraction(coreSize, fraction))` and does NOT consult the
  shrink decision. The eviction cert still requires the unshrunken Core quorum; the
  liveness path that unwedges a stuck committee runs through the phase quorums and the
  VCC/TC certificates above, not the eviction certificate.
- **Stall feasibility.** `StallDetector` reads `quorumShrinkDecision` and threads
  `shrinkDecision.quorumOverride` into its feasibility helpers
  (`engine/StallDetector.scala:173-174,304,449,1463-1528`). The override only ever
  lowers the required quorum: `quorumOverride.fold(baseRequired)(o => math.min(baseRequired, math.max(1, o)))`.

Observability (`state/StateTransitions.scala:154-182`): when a gate passes only via the
shrunken margin, `logQuorumShrinkApplied` emits one INFO line plus the
`dag_consensus_quorum_shrink_applied_total` counter (labeled by `site`) and updates the
`dag_consensus_quorum_shrink_required` gauge. The phase gate additionally logs a
`[QuorumShrink]` line at `ConsensusStateAdvancer.scala:170-174`.

---

## Per-environment activation

The rung is gated by `quorumShrinkActivationViews` (`config/types.scala:861-872`), the
number of `viewInterval` units of wall silence since the parent outcome's
`consensusEndTime` after which the escalation begins. The default `0` disables the rung
entirely, and the knob is folded into `deterministicConfigHash`, so a divergent operator
value handshake-rejects rather than silently forking.

The value is env-resolved at the consensus construction site from
`SnapshotConfig.quorumShrinkActivationViews.get(env)`, typed `Map[AppEnvironment, PosInt]`
(`config/types.scala:1175`). An ABSENT env entry leaves the resolved scalar at `0` and
disables the rung for that environment. The HOCON binding is `quorum-shrink-activation-views`
in `dag-l0.conf:177` and `currency-l0.conf:39`:

```hocon
quorum-shrink-activation-views {
  mainnet: 0
  integrationnet: 0
  dev: 0
  testnet: 10
}
```

`testnet: 10` means roughly 10 minutes of dead air at the 60s `viewInterval` before the
first reduction. The reduction applies to liveness/certificate paths; post-bootstrap
snapshot finality remains fenced by the frozen committee floor. Mainnet, integrationnet,
and dev are explicitly disabled (`0`) for a conservative launch. Because the knob is in
`deterministicConfigHash`, changing it requires a coordinated cluster-wide redeploy.

---

## Relationship to the permissioned-fallback proposal

`liveness-shrink-permissioned-fallback.md` describes a different, superseded design:
an operator-curated, BFT-bypass `LivenessShrink` vote/cert mechanism that would reduce
the eligible-peer set to a permissioned subset. That proposal was never shipped (no
`LivenessShrink` symbol exists in the codebase). The wedge it targeted was instead solved
by this rung, which shrinks the quorum **denominator** rather than the committee or a
permissioned peer set, anchors on consensus-agreed
`controllerEvidence.completedSigners` intersected with `roundStartFacilitators`, adds no
new wire types, and activates per environment via `quorum-shrink-activation-views`. Treat
the permissioned-fallback note as historical context; this document describes the live
mechanism.
