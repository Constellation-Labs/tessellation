# Consensus Configuration Reference (post-4.0)

This is the operator-facing reference for the Global L0 consensus and health configuration. Most of these knobs are **consensus-critical**: they are folded into `ConsensusConfig.deterministicConfigHash`, which L0 advertises in its cluster registration and carries in every Global `Facility` declaration. L0 joining requires exact hash equality (including presence), while Facility comparison remains an additional structured diagnostic. The advertised `versionHash` is an independent release fence; it hashes the advertised version string (or `CL_VERSION_HASH`), not jar bytes or runtime configuration. Per-environment values are resolved through `SnapshotConfig.resolveEffectiveConsensusConfig`; startup threads that exact resolved projection to both the join fence and live consensus. Most public `FieldsAddedOrdinals` remain outside this fingerprint (they enter only through `ordinalConfigHash`). The Global-only v35 certified-consensus activation key, the resolved Global L0 Currency-snapshot-protocol-v1 boundary (the v4.1 cutover `tessellation-41-migration`), and `max-round-duration` are intentional exceptions because they directly select or constrain deterministic derivation. Currency L0 uses a separate flat synchronous engine; it still uses the version/config join fences, but it does not consume the Global view/QC/admission knobs as a v35 state machine.

The Global L0 committee model is the single frozen voting set of [ADR-0036](../adr/0036-single-frozen-voting-set.md): one voting set per round, one quorum `max(1, ceil(n * quorum-threshold-fraction))`, round-robin leaders. The tier-era knobs (Core size, controller scores, leader-quality floors, Tier-1 grace) are deleted; see [Removed keys](#removed-keys-consensusschemaversion-37) below.

This document also flags three gaps an operator will hit in the field: `LocalHealthMonitorConfig` has no HOCON binding at all, and two operational toggles (`CL_MPT_VERIFY_INCREMENTAL`, `CL_RAISE_ON_FOLLOWER_DIVERGENCE`) are read directly from `sys.env` and appear in no `.conf` file.

---

## How the knobs wire into a round

Every consensus knob lives in `ConsensusConfig` (`config/types.scala`). At construction time:

1. Scalar HOCON keys under `snapshot.consensus { ... }` in `dag-l0.conf` map directly onto `ConsensusConfig` fields (kebab-case to camelCase). These are global, not per-environment.
2. Per-environment knobs live one level up under `snapshot { ... }` as `Map[AppEnvironment, T]`. The only one left on Global L0 is `certified-consensus-activation-ordinal`. `SnapshotConfig.resolveEffectiveConsensusConfig` resolves the current environment once (and copies the scalar `max-facilitator-count` into `facilitatorSelectionMax`) and returns the exact `ConsensusConfig` object used by startup, joining, and live consensus.
3. `ConsensusConfig.deterministicConfigHash` concatenates the consensus-critical fields into a stable string and hashes it. L0 advertises that exact effective hash in `RegistrationRequest`; unequal or one-sided values are rejected during joining. It is also compared during `Facility` processing for post-join diagnostics. Operators must still verify one effective hash before launch so a misconfigured fleet fails preflight instead of fragmenting during startup.

A field is consensus-critical if and only if it appears in the `deterministicConfigHash` string. The current string covers: `maxFacilitatorCount`, `facilitatorSelectionMax`, `maxStallCycles`, `signatureGracePeriodMs`, `removalPenaltyRounds`, `candidateDeferralRounds`, `readmissionProbationRounds`, `quorumThresholdFraction`, `exponentialPenaltyBase`, `maxRemovalPenaltyRounds`, `forceViewChangeAbandonments`, `viewIntervalMs`, `maxRoundDurationMs`, `tighteningWindow`, `activeFacilitatorFloor`, `activeAdmissionMaxExpansionPerRound`, `activeAdmissionExpansionIntervalRounds`, `bootstrapDeclarationTimeoutMultiplier`, `penaltyDurationOrdinals`, `certifiedConsensusActivationKey`, `lastGlobalSnapshotSyncOffset`, `lastGlobalSnapshotsInMemory`, `currencySnapshotProtocolV1ActivationOrdinal`, `ordinalConfigHash` and `consensusSchemaVersion` (now `37`). Timing-only fields (the view-apply delay, trigger cadence, declaration timeouts) are excluded. `signature-grace-period` and `max-round-duration` are hashed even though they are durations: the grace window bounds which commit signatures land in the certified commit evidence, and `max-round-duration` bounds the leader-proposed, committee-certified consensus end time.

---

## Consensus-critical knobs (must match cluster-wide)

These are folded into `deterministicConfigHash`. Divergent effective values are rejected at L0 joining and additionally reported during Facility processing. **Do not set per-operator on a shared network.**

### Global scalar knobs (`dag-l0.conf` -> `snapshot.consensus`)

| HOCON key | CL_ override | Type | Default | Meaning |
|-----------|--------------|------|---------|---------|
| `quorum-threshold-fraction` | `CL_QUORUM_THRESHOLD_FRACTION` | Double | conf: `0.6666666666666666` (compiled: `1.0`) | The one committee quorum: every phase gate, QC, VCC/TC, eviction/admission certificate and the finalization gate need `FinalityQuorum.of(n, fraction)` = `max(1, ceil(n * fraction))` over the frozen round committee. Only unanimity (`1.0`) and supermajority (the max-precision Double approximation of `2/3`) are accepted. |
| `signature-grace-period` | `CL_SIGNATURE_GRACE_PERIOD` | Duration | `3 seconds` | Once quorum is reached, finalize immediately if the whole committee has signed; otherwise wait this long from first quorum. Consensus-critical since `consensusSchemaVersion` 37 (in the hash as `signatureGracePeriodMs`) because it bounds the certified commit evidence. It is no longer a local tuning knob: a node started with a different `CL_SIGNATURE_GRACE_PERIOD` advertises a different config hash and is rejected at the L0 join fence, so set it fleet-wide in one coordinated restart or not at all. See [signature-grace.md](../consensus/signature-grace.md). |
| `force-view-change-abandonments` | `CL_FORCE_VIEW_CHANGE_ABANDONMENTS` | Int | `3` | After this many consecutive same-key abandonments, `StallDetector` force-emits a view-change vote, bypassing the missing-still-responsive gate. Strictly less than `maxConsecutiveAbandonments` (5) so the new view can converge before recovery escalation. |
| `max-stall-cycles` | none | Int | `3` | Stall cycles before a round is abandoned (`MaxStalls`). |
| `max-round-duration` | `CL_MAX_ROUND_DURATION` | Duration | `5 minutes` | V35 deterministic upper bound for the leader-proposed consensus end-time. It participates in ProposalValue validation and is therefore hash-folded. |
| `removal-penalty-rounds` | `CL_REMOVAL_PENALTY_ROUNDS` | Int | conf `5` | Base removal penalty for an evicted peer; scaled by `exponential-penalty-base` per repeat eviction and capped by `max-removal-penalty-rounds` (conf `100`). |
| `candidate-deferral-rounds` | `CL_CANDIDATE_DEFERRAL_ROUNDS` | Int | `3` | Still in the hash; no Global L0 read site remains outside the config (inert). |
| `tightening-window` | `CL_TIGHTENING_WINDOW` | Int | `10` | Size (in ordinals) of the `recentRoundEndTimes` window that anchors the view-from-time pacemaker hint. `recentCommitteeSizes` (the bootstrap signal) uses a fixed 10-ordinal lookback and is not sized by this key. |
| `active-facilitator-floor` | `CL_ACTIVE_FACILITATOR_FLOOR` | Int | `4` (GL0 conf sets `3`) | Read by the rollback first-round / ready-participation gates (minimum Ready participants, including self, before a rollback first round starts). It no longer gates admission scoring (there is none). |
| `active-admission-max-expansion-per-round` | `CL_ACTIVE_ADMISSION_MAX_EXPANSION_PER_ROUND` | Int | `1` | Maximum open admissions per admission opportunity. Penalty/probation readmission is a separate, non-cadenced lane. |
| `active-admission-expansion-interval-rounds` | `CL_ACTIVE_ADMISSION_EXPANSION_INTERVAL_ROUNDS` | Int | conf `5` (compiled `1`) | Open admission is allowed every N rounds. Also the divisor for `DeterministicRotation.opportunityIndex`, which rotates the admission nominee, admission-certificate selection and audit targets through PeerId-sorted candidates. |
| `max-facilitator-count` | none | PosInt | GL0 conf `1000` | Selector cap. Copied into `facilitatorSelectionMax`; the successor rule only rejects growth past `max(cap, |B|)` and never sheds an incumbent to enforce it. Currency L0's own `currency-l0.conf` value (`20`) is an admission-only cap in its synchronous engine. |

`view-interval` (the view-from-time pacemaker divisor) is consensus-critical (`viewIntervalMs`) but has **no HOCON key**: it is a compiled-in default of `60.seconds`. Changing it requires a code change plus a coordinated cold restart.

Compiled-in hashed fields with no HOCON key: `readmissionProbationRounds` (`3`, seeds the B2 sticky-probation countdown), `bootstrapDeclarationTimeoutMultiplier` (`2.0`, declaration-timeout multiplier while the chain is in bootstrap, i.e. while no recent round's committee reached `BootstrapCommittee.MinimumSize` = 3), `penaltyDurationOrdinals` (`100`, certificate-anchored `penaltyUntil` horizon), and `consensusSchemaVersion` (`37`).

### Per-environment knobs (`dag-l0.conf` -> `snapshot`, `Map[AppEnvironment, ...]`)

An **absent env entry** has the behavior in the table's "Absent-entry behavior" column (for `certified-consensus-activation-ordinal`, a Global L0 startup failure), so adding or removing an entry is itself a consensus-critical, coordinated change.

| HOCON key | Type | Values | Absent-entry behavior | Meaning |
|-----------|------|--------|-----------------------|---------|
| `certified-consensus-activation-ordinal` | `Map[Env, SnapshotOrdinal]` | testnet, integrationnet, dev `0` (certified from genesis); mainnet `9999999` placeholder, set to the final v3.5 ordinal R at the v4.1 cutover | **Global L0 startup fails** (`CertifiedConsensusActivationUnconfigured`, `Main.scala`) | **Global L0-only** certified-consensus boundary, interpreted in Global snapshot ordinal space. This build has no pre-v35 engine: a node downloads/replays keys below activation but refuses to produce one (`CertifiedConsensusNotActiveForProduction`), and a rollback anchor below activation is rejected. The resolved key is hash-folded and must be identical cluster-wide; see [ADR-0035](../adr/0035-v35-to-v41-mainnet-migration.md) for the mainnet cutover. Currency L0 has no configured v35 key and never switches its flat synchronous engine on this value; its shared `ConsensusConfig` projection retains only the disabled `Long.MaxValue` sentinel. |

### Cross-layer hashed values

The cross-layer `lastGlobalSnapshotSyncOffset`, `lastGlobalSnapshotsInMemory`, and
resolved `currencySnapshotProtocolV1ActivationOrdinal` (the v4.1 cutover `tessellation-41-migration`) are copied from shared config
into both DAG and Currency L0 effective `ConsensusConfig` values and hash-folded. The
last value authorizes the existing signed Currency snapshot `version` to advance from
`0.0.1` to `1.0.0` in GLOBAL L0 ordinal space. It is separate from the Global-only v35
certified-consensus key; there is no Currency-local certified-consensus key. See
[ADR-0033](../adr/0033-versioned-currency-snapshot-history.md).

`consensusSchemaVersion=37` is the immediate fence against mixed active-consensus wire versions (v37: single frozen voting set, certified wire `CertifiedConsensusSchema` v36 with `CommitQC` over the frozen committee and no Core set). The Global ordinal key separately controls when Global v35 behavior starts.

### Removed keys (`consensusSchemaVersion` 37)

These keys no longer exist. Setting them, or their former `CL_` overrides, has no effect (pureconfig ignores unknown keys), and they are not part of the hash: `core-committee-size`, `active-facilitator-target`, `active-facilitator-max`, `active-admission-promote-threshold`, `active-admission-retain-threshold`, `active-admission-demote-threshold`, `active-admission-max-score`, `active-admission-signature-reward`, `active-admission-responder-reward`, `active-admission-missed-active-penalty`, `active-admission-timeout-missing-penalty`, `active-admission-evicted-penalty`, `active-admission-degraded-penalty`, `active-admission-critical-penalty`, `active-admission-passive-decay`, `active-admission-recent-signer-window`, `active-admission-min-probation-reentry-slots`, `min-participation-observations`, `min-participation-ratio`, `min-observation-history-floor`, `tier-1-signature-grace-period`, and the compiled-in `leaderRotationMinRatioPct`, `hardLeaderQualityScorePct`, `minLeaderPoolSize`, `chronicReinstatementInterval`, `qualityDecayThreshold` and `bootstrapCompleteProofsThreshold` (now the constant `BootstrapCommittee.MinimumSize = 3`). The earlier v36 change already removed `min-participation-in-window`, `lock-on-vote-protocol-version` and `quorum-shrink-activation-views`.

---

## Timing-only consensus knobs (NOT in the hash)

These affect liveness/latency, not what is decided. Mixed values across a network are safe but can produce noisy early/late behavior; keep them in the same practical band.

| HOCON key | CL_ override | Type | Default | Meaning |
|-----------|--------------|------|---------|---------|
| (no key) `view-change-apply-delay` | none | Duration | `7 seconds` | Delay between assembling/receiving a certified view change and locally applying it, so ordinary traffic can arrive first. |
| `time-trigger-interval` | `CL_TIME_TRIGGER_INTERVAL` | Duration | `43 seconds` | Regular round-trigger cadence. |
| `declaration-timeout` | `CL_DECLARATION_TIMEOUT` | Duration | `45 seconds` | Base per-phase declaration timeout for stall detection (multiplied by `bootstrapDeclarationTimeoutMultiplier` during bootstrap). |

`b2AdmissionAtTipStreak` (default `2`) is also timing-only and NOT in the hash. It has **no HOCON key** (compiled-in default only): it is the number of consecutive fresh exact-parent observations a probation peer must present before this node emits an `AdmissionVote`. Two honest nodes may diverge in their per-peer streaks without affecting safety; cert assembly still requires quorum-agreed signed votes. Values `<= 0` are clamped to `1` at the read site.

Global-L0 open admission has an additional non-configurable local safety invariant. A
committee member emits an `AdmissionVote` only when the actual locally observed parent
proofs from current committee members already meet the finality floor for a committee
one seat larger, the canonical nominee returns authenticated metadata naming the exact
parent, and that voter has received the nominee's authenticated Facility bound to the
same current round. Cached nearby-tip evidence cannot suppress or replace the fresh
direct probe. These local checks prevent a Ready follower whose consensus FSM is one
round behind from entering the next finality denominator. This is deliberately not part
of proposal validation or state derivation, because valid proof subsets can differ
between honest nodes. Under v35, atomic replacement uses node-local actual-proof history
(`FinalityParticipationAuditor`, `DefaultRequiredConsecutiveMisses` = `3`, plus an
elapsed-time floor); no operator knob exists for it. The resulting eviction evidence is
valid only when paired one-for-one with a committee-certified ReadyAtTip admission, so
this policy cannot shrink the committee.

---

## GAP 1: LocalHealthMonitorConfig has no HOCON binding

`LocalHealthMonitorConfig` (`config/types.scala`) carries:

- `pollInterval` (default `10.seconds`)
- `historyWindow` (default `5.minutes`)
- `gcPauseDegradedMs` / `gcPauseCriticalMs` (defaults `5000` / `30000`)
- `loadPerVcpuDegraded` / `loadPerVcpuCritical` (defaults `3.0` / `6.0`)
- `operatorOverride: Option[SelfHealthHint]` (default `None`)

It is constructed **only** from compiled-in defaults: `SharedConfig.localHealthMonitor` defaults to `LocalHealthMonitorConfig.default` (`config/types.scala`) and is set from no HOCON key and no CLI argument (it is absent from `SharedConfigReader`, `CliMethod`, and `TessellationIOApp`). There is no `consensus { local-health-monitor { ... } }` block in any `*.conf`.

**Operator impact:** none for consensus. Since [ADR-0036](../adr/0036-single-frozen-voting-set.md) self-health has no consensus effect: there is no `Facility.selfHealthHint`, no leader-aggregated health, and no health-gated leader pool. `LocalHealthMonitor` only publishes operator gauges (`dag_node_self_health`, `dag_node_self_health_gc_pause_max_recent_seconds`, `dag_node_self_health_load_per_vcpu`). `operatorOverride` only changes the locally published gauge, and it is not reachable from HOCON or the CLI. The thresholds are not in `deterministicConfigHash` and do not need to be, because no peer's hint reaches another node's consensus.

---

## GAP 2: env-only operational toggles (no HOCON, no .conf)

Two toggles are read directly via `sys.env.get` and so appear in no `.conf` file -- an operator grepping `application.conf` / `dag-l0.conf` will not find them. Both default OFF and take effect per process, via the environment, not via config reload.

| Env var | Default | Effect | When to enable |
|---------|---------|--------|----------------|
| `CL_MPT_VERIFY_INCREMENTAL` | OFF | On the incremental acceptance path, independently rebuilds the full MPT root from the swept GSI and compares it to the incremental store root, logging `[MPT.VERIFY] ... INCREMENTAL DRIFT` on mismatch. Log-only, lazy, and swallows all rebuild errors -- it can never affect acceptance or fork a node that has it set (`GlobalSnapshotAcceptanceManager.scala:1163-1186`). | During an MPT divergence hunt, to catch incremental-vs-full drift at the ordinal it is introduced. Safe to leave on as a diagnostic. |
| `CL_RAISE_ON_FOLLOWER_DIVERGENCE` | OFF | When a follower's local re-reconstruction of an L0-validated snapshot diverges (blocks/state-channels/rewards the signed snapshot included but this node rejects), the default is to log `[FOLLOWER-STATE-DIVERGENCE]` and continue. Set true to instead raise `GlobalStateDivergenceError` and halt, so the caller's recovery path re-syncs the correct state (`GlobalSnapshotContextFunctions.scala:337, 351, 378-384`). | Only on followers / in recovery, where halting and re-syncing is preferable to proceeding on a possibly-forked global state. Has a real availability cost: it converts a warning into a hard halt. |

Neither toggle is in `deterministicConfigHash`; they change local behavior only.

---

## Quick operator rules

1. **Never** override a knob listed in the consensus-critical tables on a shared network. The value is in `deterministicConfigHash`; divergent L0 peers are rejected during joining.
2. The per-environment Global knob `certified-consensus-activation-ordinal` must match the complete Global cluster; **adding or removing an env entry is a consensus change** because absent means a specific resolved default (for the activation ordinal, a startup failure). Currency has no v35 activation entry.
3. Changing any hashed knob (including the compiled-in `view-interval`) requires an all-or-nothing coordinated cold restart because divergent configs can derive and finalize different state. The advertised `versionHash` is only a release fence and does not certify runtime config equality; verify the effective `deterministicConfigHash` fleet-wide before launch. The v35 activation ordinal separately coordinates the certified state-machine boundary.
4. Genuinely operator-safe knobs are the non-consensus ones: snapshot-serving rate limits (`snapshot-serving { ... }`), storage paths, and the timing-only trigger/timeout knobs. `signature-grace-period` and `max-round-duration` are NOT operator-safe: both are hashed.
5. `LocalHealthMonitorConfig` has no operator knobs in this release, and self-health has no consensus effect. `CL_MPT_VERIFY_INCREMENTAL` and `CL_RAISE_ON_FOLLOWER_DIVERGENCE` are env-only diagnostics/safety toggles, both OFF by default.

---

## Source of truth

- Config schema and `deterministicConfigHash`: `modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/config/types.scala`
- Deployed values: `modules/dag-l0/src/main/resources/dag-l0.conf`
- Signature grace: `modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/infrastructure/consensus/SignatureGraceDecision.scala`
- Env toggles: `GlobalSnapshotAcceptanceManager.scala:1163-1186`, `GlobalSnapshotContextFunctions.scala:337-384`
