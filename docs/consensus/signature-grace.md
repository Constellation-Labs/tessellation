# Signature Grace Window

`SignatureGraceDecision` is a small, pure state machine that lets a consensus
round keep collecting `MajoritySignature` declarations for a bounded window
*after* it has already crossed the finalization quorum, instead of committing the
instant quorum is reached. Without it, a round that crosses quorum in the first
1-3ms on a small cluster would finalize with a truncated proof set: late but
honest signers are dropped from `signedArtifact.proofs`, and because rewards
follow the proofs, reward share collapses onto whoever happened to sign first.
The window length is chosen three ways depending on which signatures are still
outstanding, so neither finalization liveness nor reward fairness is sacrificed.
Source of truth: `SignatureGraceDecision.scala:5-83`.

This belongs to the same `CollectingSignatures -> Finished` transition described
in [README sec 5](README.md#5-consensus-round-phases) and the finalization
threshold in [README sec 15](README.md#15-signature-threshold). The threshold
decides *whether* the round may finalize; the grace window decides *when*.

## The three-way decision

`SignatureGraceDecision.evaluate` is invoked on every signatures-phase tick and
returns an `Eval`. The decision tree (`SignatureGraceDecision.scala:58-82`):

| Case | Condition | Behavior |
|------|-----------|----------|
| Not yet at quorum | `!canFinalize` | `Leave` the stamp untouched, do not finalize (the round simply has not crossed quorum yet). |
| Full committee signed | `fullCommitteeSigned` | `Clear` the stamp and finalize immediately. Nothing more can arrive, so any wait is pure latency. |
| Core complete, committee not full | `coreComplete` | Wait the SHORT `tier1Window`, measured from when Core *first* completed, then finalize. Only Tier-1 (non-quorum) signatures are outstanding; give them a brief, bounded chance to land for reward inclusion. |
| Core incomplete | otherwise | Wait the FULL `fullWindow`, measured from first quorum, for the missing quorum-bearing Core signer. This is the liveness-relevant case. |

`Eval.waitMore` is the load-bearing output: `true` means "do not finalize this
tick, keep collecting." It is computed as `(now - graceStart) < window`
(`SignatureGraceDecision.scala:81`), where `graceStart` and `window` are the
anchor/length pair selected by the case above.

### Why the Tier-1 window is anchored at Core-complete, not first-quorum

In the Core-complete case the window is measured from `coreCompleteFirstSeen`,
the monotonic time at which every Core member had first signed
(`SignatureGraceDecision.scala:75-79`), NOT from `quorumFirstSeen`. If a round's
Core completes late -- more than `tier1Window` after quorum was first crossed --
anchoring the Tier-1 collection at first-quorum would make the window already
expired by the time Core finishes, so the round would skip Tier-1 collection
entirely and concentrate rewards on Core. This is the alpha.153 regression the
Core-complete anchor fixes (`SignatureGraceDecision.scala:13-15`).

## State: the per-round Stamp

The object itself is pure and stateless. The caller owns a per-round `Stamp`
held in a `Ref`-backed map keyed by round key, and applies the returned
`StampUpdate` to it. The `Stamp` carries three fields
(`SignatureGraceDecision.scala:32-36`):

- `quorumFirstSeen` -- monotonic time the round first crossed the finalization
  quorum (the Core-incomplete window anchor).
- `firstCount` -- signature count observed at `quorumFirstSeen` (diagnostic; used
  to derive the late-added count).
- `coreCompleteFirstSeen: Option[FiniteDuration]` -- monotonic time every Core
  member had first signed; `None` until Core completes (the Tier-1 window anchor).

`evaluate` returns one of three `StampUpdate`s the caller folds into its map
(`SignatureGraceDecision.scala:39-42`):

- `Leave` -- leave the map unchanged (round not at quorum yet).
- `Clear` -- remove this round's stamp (full committee signed, finalizing now).
- `Set(stamp)` -- store/refresh this round's stamp while still waiting.

Keeping the decision pure makes the state machine directly unit-testable; the
alpha.153 grace failure had no direct coverage when the logic lived inline. The
tests live in `SignatureGraceDecisionSuite.scala`.

## How it wires into the round

The only caller is the global L0 advancer's `CollectingSignatures -> Finished`
transition, `GlobalSnapshotConsensusStateAdvancer.scala`. The per-key stamp map
is a field on the advancer:

```scala
private val signatureQuorumFirstSeenRef
  : Ref[F, Map[GlobalSnapshotKey, SignatureGraceDecision.Stamp]] = Ref.unsafe(Map.empty)
```

(`GlobalSnapshotConsensusStateAdvancer.scala:163`.)

On each signatures-phase tick the advancer derives the inputs from the canonical
round-start committee and the valid signatures collected so far
(`GlobalSnapshotConsensusStateAdvancer.scala:3196-3203`):

- `fullCommittee = state.roundStartFacilitators.value.size` (the full canonical
  committee: Core + Tier-1).
- `coreSize = state.coreFacilitators.value.size`.
- `coreComplete` -- every Core member has signed.
- `fullCommitteeSigned` -- `validSignatures.size >= fullCommittee`.
- `canFinalize` -- `validSignatures.size >= quorumThreshold` (the `(coreSize/2)+1`
  Core-only finalization threshold), or the v33 quorum-denominator-shrink
  `shrunkPath` admits the signer set (`...Advancer.scala:3216`).

It then calls `evaluate` inside `signatureQuorumFirstSeenRef.modify`, folding the
returned `StampUpdate` into the map and reading `waitMore` /`firstObserved` /
`graceStart` back out (`GlobalSnapshotConsensusStateAdvancer.scala:3261-3282`):

```scala
val eval = SignatureGraceDecision.evaluate(
  now = now,
  validCount = validSignatures.size,
  canFinalize = canFinalize,
  fullCommitteeSigned = fullCommitteeSigned,
  coreComplete = coreComplete,
  existing = m.get(state.key),
  tier1Window = config.tier1SignatureGracePeriod,
  fullWindow = config.signatureGracePeriod
)
```

If `waitMore` is true the transition returns `none[Transition]` -- the round
stays in `CollectingSignatures` and re-evaluates on the next tick
(`...Advancer.scala:3311-3312`). The signatures-phase heartbeat that drives those
ticks comes from `StallDetector`, which re-queues `CheckUpdate` every tick while
in any signatures-collecting phase precisely so this gate is re-evaluated without
waiting for a new signature to arrive (see [README sec 11](README.md#11-stall-detection--eviction)).
When the window has elapsed and `canFinalize` holds, the transition clears the
stamp (`signatureQuorumFirstSeenRef.update(_ - state.key)`) and builds the
`Signed[Artifact]` from all collected proofs (`...Advancer.scala:3313-3320`).

## Configuration

Two `ConsensusConfig` durations supply the window lengths
(`config/types.scala:238-251`):

| Config field | Default | Role |
|--------------|---------|------|
| `signatureGracePeriod` | `3.seconds` | `fullWindow` -- the Core-incomplete case, measured from first quorum. |
| `tier1SignatureGracePeriod` | `750.milliseconds` | `tier1Window` -- the Core-complete case, measured from first Core completion (roughly `signatureGracePeriod / 4`). |

Both are timing-only and are deliberately NOT folded into
`deterministicConfigHash` (`config/types.scala:245-250`). The canonical
`snapshotHash` is the agreed *artifact* hash, not the signed-artifact hash, so two
nodes running different grace periods still produce the same downstream
`snapshotHash`. This lever only changes which proofs ride along on the signed
artifact (and therefore the reward split), never a consensus-decided value, so it
does not need to match cluster-wide and a divergent value will not fork.

## Observability

The advancer emits grace-specific metrics around the `evaluate` call
(`GlobalSnapshotConsensusStateAdvancer.scala:3283-3318`):

- `dag_consensus_signature_quorum_reached_total` -- incremented on the first tick
  quorum is observed (`firstObserved`).
- `dag_consensus_signature_grace_wait_total` and
  `dag_consensus_signature_grace_wait_time` -- recorded while `waitMore` holds.
- `dag_consensus_signature_grace_current_valid_count` /
  `dag_consensus_signature_grace_committee_size` gauges during the wait.
- `dag_consensus_signature_late_added_count` -- on finalize, signatures collected
  during the grace window beyond the first-quorum count.
