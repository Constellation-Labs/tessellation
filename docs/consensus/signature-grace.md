# Signature Grace Window

`SignatureGraceDecision` is a small, pure state machine that lets a Global L0
consensus round keep collecting `MajoritySignature` declarations for a bounded
window *after* it has already crossed the finalization quorum, instead of
committing the instant quorum is reached. Without it, a round that crosses quorum
in the first 1-3ms on a small cluster would finalize with a truncated proof set and
truncated commit evidence. Delegated rewards do not follow proofs; they follow frozen
round membership. Source of truth: `SignatureGraceDecision.scala`.

This belongs to the same `CollectingSignatures -> Finished` transition described
in [README sec 5](README.md#5-consensus-round-phases) and the finalization
threshold in [README sec 15](README.md#15-signature-threshold). The threshold
decides *whether* the round may finalize; the grace window decides *when*.

Before [ADR-0036](../adr/0036-single-frozen-voting-set.md) the decision had three
cases (a short Tier-1 window once Core had signed, and a full window otherwise).
With one frozen voting set there is no Core and no Tier 1, so the decision is
two-way and uses one window.

## The two-way decision

`SignatureGraceDecision.evaluate` is invoked on every signatures-phase tick and
returns an `Eval`:

| Case | Condition | Behavior |
|------|-----------|----------|
| Not yet at quorum | `!canFinalize` | `Leave` the stamp untouched, do not finalize (the round has not crossed quorum yet). |
| Full committee signed | `fullCommitteeSigned` | `Clear` the stamp and finalize immediately. Nothing more can arrive, so any wait is pure latency. |
| Quorum, committee not full | otherwise | Wait `window` (`signatureGracePeriod`), measured from when quorum was first seen, then finalize. |

`Eval.waitMore` is the load-bearing output: `true` means "do not finalize this
tick, keep collecting." In the third case it is `(now - quorumFirstSeen) < window`.

## State: the per-round Stamp

The object itself is pure and stateless. The caller owns a per-round `Stamp`
held in a `Ref`-backed map keyed by round key, and applies the returned
`StampUpdate` to it. The `Stamp` carries two fields:

- `quorumFirstSeen` -- monotonic time the round first crossed the finalization
  quorum (the window anchor).
- `firstCount` -- signature count observed at `quorumFirstSeen` (diagnostic; used
  to derive the late-added count).

`evaluate` returns one of three `StampUpdate`s the caller folds into its map:

- `Leave` -- leave the map unchanged (round not at quorum yet).
- `Clear` -- remove this round's stamp (full committee signed, finalizing now).
- `Set(stamp)` -- store/refresh this round's stamp while still waiting.

Keeping the decision pure makes it directly unit-testable; the tests live in
`SignatureGraceDecisionSuite.scala`.

## How it wires into the round

The only caller is the Global L0 advancer's `CollectingSignatures -> Finished`
transition (`buildFinishedTransition` in `GlobalSnapshotConsensusStateAdvancer.scala`).
The per-key stamp map is a field on the advancer:

```scala
private val signatureQuorumFirstSeenRef
  : Ref[F, Map[GlobalSnapshotKey, SignatureGraceDecision.Stamp]] = Ref.unsafe(Map.empty)
```

On each signatures-phase tick the advancer derives the inputs from the frozen
round committee and the valid signatures collected so far:

- `fullCommitteeSigned` -- `validSignatures.size >= state.roundStartFacilitators.value.size`.
- `canFinalize` -- `validSignatures.size >= committeeQuorum(state)`, where
  `committeeQuorum` is `FinalityQuorum.of(|roundStartFacilitators|, quorumThresholdFraction)`.
  This is the same quorum every phase gate and certificate uses; there is no
  separate bootstrap finality path.

It then calls `evaluate` inside `signatureQuorumFirstSeenRef.modify`, folding the
returned `StampUpdate` into the map and reading `waitMore` / `firstObserved` /
`graceStart` back out:

```scala
val eval = SignatureGraceDecision.evaluate(
  now = now,
  validCount = validSignatures.size,
  canFinalize = canFinalize,
  fullCommitteeSigned = fullCommitteeSigned,
  existing = m.get(state.key),
  window = config.signatureGracePeriod
)
```

If `waitMore` is true the transition returns `none[Transition]` -- the round
stays in `CollectingSignatures` and re-evaluates on the next tick. The
signatures-phase heartbeat that drives those ticks comes from `StallDetector`,
which re-queues `CheckUpdate` every tick while in any signatures-collecting phase
so this gate is re-evaluated without waiting for a new signature to arrive (see
[README sec 11](README.md#11-stall-detection--eviction)). When the window has
elapsed and `canFinalize` holds, the transition clears the stamp and builds the
`Signed[Artifact]` and the certified `CommitQC` from all collected signatures.

## Configuration

| Config field | HOCON key | Default | Role |
|--------------|-----------|---------|------|
| `signatureGracePeriod` | `snapshot.consensus.signature-grace-period` (`CL_SIGNATURE_GRACE_PERIOD`) | `3 seconds` | The one grace window, measured from first quorum. |

`signatureGracePeriod` is **consensus-critical**: it is folded into
`ConsensusConfig.deterministicConfigHash` (as `signatureGracePeriodMs`). The
window bounds which commit signatures land in the certified commit evidence, and
the planned membership-clearing change (not yet implemented) will count that
evidence, so per-node windows would bias it. Every node in a cluster must run the
same value, and the L0 join fence rejects a peer whose effective hash differs.
Changing it is a coordinated cold-restart change.

The former `tier-1-signature-grace-period` / `tier1SignatureGracePeriod` key is
deleted.

## Observability

The advancer emits grace-specific metrics around the `evaluate` call:

- `dag_consensus_signature_quorum_reached_total` -- incremented on the first tick
  quorum is observed (`firstObserved`).
- `dag_consensus_signature_grace_wait_total` and
  `dag_consensus_signature_grace_wait_time` -- recorded while `waitMore` holds.
- `dag_consensus_signature_grace_current_valid_count` /
  `dag_consensus_signature_grace_committee_size` gauges during the wait.
- `dag_consensus_signature_grace_final_wait_time` -- total wait at finalize.
- `dag_consensus_signature_late_added_count` -- on finalize, signatures collected
  during the grace window beyond the first-quorum count.
