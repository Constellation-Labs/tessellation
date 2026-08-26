# Consensus Configuration Reference (post-4.0)

This is the operator-facing reference for the consensus and health configuration introduced into the Global L0 consensus path since v4.0.0. Most of these knobs are **consensus-critical**: they are folded into `ConsensusConfig.deterministicConfigHash`, which L0 advertises in its cluster registration and carries in every Global `Facility` declaration. L0 joining requires exact hash equality (including presence), while Facility comparison remains an additional structured diagnostic. The advertised `versionHash` is an independent release fence; it hashes the advertised version string (or `CL_VERSION_HASH`), not jar bytes or runtime configuration. Per-environment values are resolved through `SnapshotConfig.resolveEffectiveConsensusConfig`; startup threads that exact resolved consensus-critical projection to both the join fence and live consensus. Most public `FieldsAddedOrdinals` remain outside this fingerprint. The Global-only v35 certified-consensus activation key, the resolved Global L0 Currency-snapshot-protocol-v1 gate, and `max-round-duration` are intentional exceptions because they directly select or constrain deterministic derivation. Currency L0 uses a separate flat synchronous engine; it still uses the version/config join fences, but it does not consume Core/tier/view/QC knobs as a v35 state machine.

This document then flags three gaps an operator will hit in the field: `LocalHealthMonitorConfig` has no HOCON binding at all, and two operational toggles (`CL_MPT_VERIFY_INCREMENTAL`, `CL_RAISE_ON_FOLLOWER_DIVERGENCE`) are read directly from `sys.env` and appear in no `.conf` file.

---

## How the knobs wire into a round

Every consensus knob lives in `ConsensusConfig` (`config/types.scala`). At construction time:

1. Scalar HOCON keys under `snapshot.consensus { ... }` in `dag-l0.conf` map directly onto `ConsensusConfig` fields (kebab-case to camelCase). These are global, not per-environment.
2. Per-environment knobs live one level up under `snapshot { ... }` as `Map[AppEnvironment, T]` (e.g. `core-committee-size`, `quorum-shrink-activation-views`). `SnapshotConfig.resolveEffectiveConsensusConfig` resolves the current environment once and returns the exact `ConsensusConfig` object used by startup, joining, and live consensus.
3. `ConsensusConfig.deterministicConfigHash` concatenates the consensus-critical fields into a stable string and hashes it. L0 advertises that exact effective hash in `RegistrationRequest`; unequal or one-sided values are rejected during joining. It is also compared during `Facility` processing for post-join diagnostics. Operators must still verify one effective hash before launch so a misconfigured fleet fails preflight instead of fragmenting during startup.

A field is consensus-critical if and only if it appears in the `deterministicConfigHash` string. Pure timing fields (grace windows and view-apply delay) are deliberately excluded, because the canonical `snapshotHash` is the agreed *artifact* hash, not the signed-artifact hash. V35 is the exception for `max-round-duration`: it bounds the leader-proposed, Core-certified consensus end-time and therefore joins the deterministic hash.

---

## Consensus-critical knobs (must match cluster-wide)

These are folded into `deterministicConfigHash`. Divergent effective values are rejected at L0 joining and additionally reported during Facility processing. **Do not set per-operator on a shared network.**

### Global scalar knobs (`dag-l0.conf` -> `snapshot.consensus`)

| HOCON key | CL_ override | Type | Default | Meaning |
|-----------|--------------|------|---------|---------|
| `quorum-threshold-fraction` | `CL_QUORUM_THRESHOLD_FRACTION` | Double | conf: `0.6666666666666666` (compiled: `1.0`) | Fraction of `N` declarations needed to advance a phase / assemble a cert. `ceil(N * fraction)`. The compiled-in default is `1.0` (unanimity, `config/types.scala:389`); testnet sets supermajority (`2/3`), the max-precision Double approximation of exact `2/3` (`dag-l0.conf:59-71`). |
| `force-view-change-abandonments` | `CL_FORCE_VIEW_CHANGE_ABANDONMENTS` | Int | `3` | After this many consecutive same-key abandonments, `StallDetector` force-emits a view-change vote, bypassing the missing-still-responsive gate. Strictly less than `max-consecutive-abandonments` (5) so the new view can converge before recovery escalation (`config/types.scala:260-272`). |
| `tightening-window` | `CL_TIGHTENING_WINDOW` | Int | `10` | Size (in ordinals) of the rolling `recentSigners` window that feeds tier-demotion hysteresis. LIVE (`config/types.scala:296-315`). |
| `min-participation-in-window` | `CL_MIN_PARTICIPATION_IN_WINDOW` | Int | `6` | INERT (dead config). Parameterized the retired v19 active-set tightening filter; kept only to keep the schema/hash unchanged (`config/types.scala:303-310`). |
| `active-facilitator-floor` | `CL_ACTIVE_FACILITATOR_FLOOR` | Int | `4` (GL0 conf sets `3`) | Emergency active-admission bypass threshold and rollback / ready-participation floor. At or above it, recent-signer score gating remains enabled; below it, admission falls back to the selected pool for bootstrap or genuine collapse. Deliberately independent of `core-committee-size`; folded into `deterministicConfigHash`. |
| `active-facilitator-target` | (override removed) | Map[AppEnvironment, Int] | `2c+1` of `core-committee-size`: mainnet `31`, integrationnet `19`, testnet `7`, dev `7` | Target for the score/recent-signer Core classification. It is not a signing-committee, reward, or certified-admission ceiling. Per-env map, resolved once and folded into `deterministicConfigHash`. MUST exceed `core-committee-size`; the operator override remains removed because divergent values alter tier classification. |
| `active-facilitator-max` | (override removed) | Map[AppEnvironment, Int] | `4c+1`: mainnet `61`, integrationnet `37`, testnet `13`, dev `13` | Maximum input size for the controller's Core-eligible classification and probation headroom. It does not cap retained Core + Tier-1 signing/reward membership. Per-env map, hash-folded, and MUST be `>= core-committee-size`. |
| `min-participation-observations` | `CL_MIN_PARTICIPATION_OBSERVATIONS` | Int | conf: `10` | Threshold at which the chronic non-signer filter / graduated leader pool engages. |
| `min-participation-ratio` | `CL_MIN_PARTICIPATION_RATIO` | Double | conf: `0.5` | Ratio below which a peer loses Core/leader eligibility and is retained as Tier 1 when otherwise eligible. Reverted from a 0.7 tightening (`dag-l0.conf:133-135`). |
| `min-observation-history-floor` | `CL_MIN_OBSERVATION_HISTORY_FLOOR` | Int | conf: `10` | Minimum `participated` count before chronic classification can fire (`dag-l0.conf:136-138`). |
| `active-admission-*` (promote/retain/demote thresholds, max-score, rewards, penalties, decay, expansion) | `CL_ACTIVE_ADMISSION_*` (`dag-l0.conf:105-132`) | Int | see `dag-l0.conf:105-132` | The integral score controller classifies Core eligibility. Certified open admission separately grows Tier-1 signing leases, bounded by `active-admission-max-expansion-per-round` and enabled only every `active-admission-expansion-interval-rounds` (shipped value: `5`). Penalty/probation readmission is a separate, non-cadenced recovery lane. Values remain consensus-critical because they affect tier and admission derivation. |
| `lock-on-vote-protocol-version` | `CL_LOCK_ON_VOTE_PROTOCOL_VERSION` | Int | conf: `2` | Lock-on-vote protocol version selector. |
| `max-round-duration` | `CL_MAX_ROUND_DURATION` | Duration | `5 minutes` | V35 deterministic upper bound for the leader-proposed consensus end-time. It participates in ProposalValue validation and is therefore hash-folded. |

`view-interval` (the v19 view-from-time pacemaker divisor) is consensus-critical (`viewIntervalMs` is in the hash, `config/types.scala:980`) but has **no HOCON key**: it is a compiled-in default of `60.seconds` (`config/types.scala:295`, raised from 30s; see the field comment at `config/types.scala:285-294`). Changing it requires a code change plus a coordinated cold restart.

### Per-environment knobs (`dag-l0.conf` -> `snapshot`, `Map[AppEnvironment, ...]`)

These resolve once per environment at the construction site (the coreCommitteeSize pattern) and are then folded into the hash. An **absent env entry** means the safe default (usually "disabled"), so adding or removing an entry is itself a consensus-critical, coordinated change.

| HOCON key | Type | Testnet value | Absent-entry behavior | Meaning |
|-----------|------|---------------|-----------------------|---------|
| `max-facilitator-count` | `Map[Env, PosInt]` | all shipped environments `1000` | no selector cap | Environment-resolved cap consumed by Global `FacilitatorSelector`. This is fingerprinted separately from the legacy scalar `snapshot.consensus.max-facilitator-count`. Currency's flat synchronous admission uses that legacy scalar (`20`) as an admission-only committee cap; it never sheds an incumbent to enforce it. A value at or above the Global candidate population disables narrowing; it is not a desired reward-population size. |
| `core-committee-size` | `Map[Env, PosInt]` | testnet `3`, mainnet `15`, integrationnet `9`, dev `3` (`dag-l0.conf:156-168`) | resolved default `3` (`config/types.scala:1023`) | The Core committee floor. The LIVENESS quorum threshold is `ceil(coreFacilitators.size * quorumThresholdFraction)`, so this is the quorum denominator. Demotions to Tier-1 outside Core do not shrink it without consensus-agreed promotion of replacements (`config/types.scala:847-860, 1153-1167`). |
| `quorum-shrink-activation-views` | `Map[Env, PosInt]` | testnet `10` (`dag-l0.conf:177-179`) | **disabled** (resolved `0`) | v33 `QuorumDenominatorShrink`: number of `view-interval` units of wall silence since the parent outcome closed before the escalating quorum-denominator shrink begins. Trades partition safety for liveness in its deep stage. Mainnet and dev are absent on purpose (`config/types.scala:861-872, 1168-1175`). |
| `active-admission-min-probation-reentry-slots` | `Map[Env, Int]` | testnet `3`, mainnet `15`, integrationnet `9`, dev `3` | disabled (resolved `0`) | Minimum bounded probation cohort classified outside Core even when the controller's per-round expansion budget is exhausted. A peer that signs the latest round retains classifier priority until it reaches the retain band; missing ends that priority, not its signing lease. Current public GL0 environments configure one Core-sized cohort. |
| `active-admission-recent-signer-window` | `Map[Env, Int]` (override `CL_ACTIVE_ADMISSION_RECENT_SIGNER_WINDOW`) | testnet `10` (`dag-l0.conf:216-219`) | floored to `3` (`DemotionConsecutiveMisses`) | Recent-signer controller lookback depth (in ordinals): how far back a peer may have last participated and still hold sticky Core-classification priority. It does not cap retained Tier-1 signing/reward membership (`config/types.scala:1185-1193`). |
| `certified-consensus-activation-ordinal` | `Map[Env, SnapshotOrdinal]` | dev `0`; all public DAG and Currency entries absent | **disabled** (`SnapshotOrdinal.MaxValue`) | Per-L0 v35 behavior boundary. DAG L0 and every Currency L0 interpret this in their own ordinal space. The resolved key is hash-folded and must be installed cluster-wide before the announced activation. |

### Other hashed knobs without their own table row

`readmissionProbationRounds` (default `3`, `config/types.scala:224`; compiled-in, no HOCON key) seeds the B2 sticky-probation countdown and is in the hash. `coreCommitteeSize`, `consensusSchemaVersion` (now `35`), the resolved certified-consensus activation key, and `qualityDecayThreshold` are also folded in. `consensusSchemaVersion=35` is the immediate fence against mixed active-consensus wire versions; the ordinal key separately controls when v35 behavior starts.

The cross-layer `lastGlobalSnapshotSyncOffset`, `lastGlobalSnapshotsInMemory`, and
resolved `currencySnapshotProtocolV1ActivationOrdinal` are copied from shared config
into both DAG and Currency L0 effective `ConsensusConfig` values and hash-folded. The
last value authorizes the existing signed Currency snapshot `version` to advance from
`0.0.1` to `1.0.0` in GLOBAL L0 ordinal space; it is separate from each cluster's
Currency-local certified-consensus key. See
[ADR-0033](../adr/0033-versioned-currency-snapshot-history.md).

---

## Timing-only consensus knobs (NOT in the hash)

These affect liveness/latency, not what is decided. Mixed values across a network are safe but can produce noisy early/late behavior; keep them in the same practical band.

| HOCON key | CL_ override | Type | Default | Meaning |
|-----------|--------------|------|---------|---------|
| `signature-grace-period` | `CL_SIGNATURE_GRACE_PERIOD` | Duration | `3 seconds` | The FULL grace window: when Core is incomplete, the round keeps collecting signatures this long (from first quorum) so a quorum-bearing Core signer can still land (`config/types.scala:230-238`). |
| `tier-1-signature-grace-period` | `CL_TIER_1_SIGNATURE_GRACE_PERIOD` | Duration | `750 milliseconds` | The SHORT grace window used once Core is fully signed but the committee is not, to let late Tier-1 signatures land in `signedArtifact.proofs` and participation evidence. It does not select delegated reward recipients. Measured from Core-complete, not first quorum (`config/types.scala:239-251`). |
| (no key) `view-change-apply-delay` | none | Duration | `7 seconds` | Delay between assembling/receiving a certified view change and locally applying it, so ordinary traffic can arrive first (`config/types.scala:252-259`). |
| `time-trigger-interval` | `CL_TIME_TRIGGER_INTERVAL` | Duration | `43 seconds` | Regular round-trigger cadence (`dag-l0.conf:11-12`). |

### Signature grace state machine

The grace window length is a pure three-way decision (`SignatureGraceDecision.evaluate`, `infrastructure/consensus/SignatureGraceDecision.scala:58-82`):

1. **Full committee signed** -> finalize immediately; no further signature can arrive.
2. **Core complete, committee not full** -> wait the short `tier1Window` (`tier-1-signature-grace-period`), anchored at when Core *first* completed, to collect late Tier-1 signatures for artifact/evidence completeness.
3. **Core incomplete** -> wait the full `fullWindow` (`signature-grace-period`), anchored at first quorum, for the missing quorum-bearing Core signer. This is the liveness-relevant case.

Anchoring case 2 at Core-complete (not first-quorum) fixes the alpha.153 regression where finalizing the instant Core completed dropped every Tier-1 proof. Delegated rewards are committee-based (`SignatureGraceDecision.scala:11-16`; ADR-0028).

`b2AdmissionAtTipStreak` (default `2`, `config/types.scala:514`) is also timing-only and NOT in the hash. It has **no HOCON key** (compiled-in default only): it is the number of consecutive monitor ticks a probation peer must present the committed tip before this node emits an `AdmissionVote`. Two honest nodes may diverge in their per-peer streaks without affecting safety; cert assembly still requires quorum-agreed signed votes. Values `<= 0` are clamped to `1` at the read site (`StallDetector.scala:1330`).

Global-L0 open admission has an additional non-configurable local safety invariant. A
Core voter emits only when the actual locally observed parent proofs from current
committee members already meet the exact finality floor for a committee one seat
larger, the canonical nominee returns authenticated metadata naming the exact parent,
and that voter has received the nominee's authenticated Facility bound to the same current
round. Cached nearby-tip evidence cannot suppress or replace the fresh direct probe.
These local checks prevent a Ready follower whose consensus FSM is one round behind
from entering the next finality denominator. The invariant starts outside bootstrap
with the existing full-committee finality floor. Bootstrap retains its legacy Core-only finality gate, which is not
raised by a newly admitted Tier-1 seat. This is deliberately not part of proposal
validation or state derivation, because valid proof subsets can differ between honest
nodes. Under v35, all-signing-member atomic replacement likewise uses node-local
actual-proof history and reuses the compiled-in
`TierTransitions.DemotionConsecutiveMisses` value (`3`); no new operator knob was
added. The resulting eviction evidence is valid only when paired one-for-one with a
Core-certified ReadyAtTip admission, so this policy cannot shrink the signing roster.

---

## GAP 1: LocalHealthMonitorConfig has no HOCON binding

`LocalHealthMonitorConfig` (`config/types.scala:82-90`) carries:

- `pollInterval` (default `10.seconds`)
- `historyWindow` (default `5.minutes`)
- `gcPauseDegradedMs` / `gcPauseCriticalMs` (defaults `5000` / `30000`)
- `loadPerVcpuDegraded` / `loadPerVcpuCritical` (defaults `3.0` / `6.0`)
- `operatorOverride: Option[SelfHealthHint]` (default `None`)

It is constructed **only** from compiled-in defaults: `SharedConfig.localHealthMonitor` defaults to `LocalHealthMonitorConfig.default` (`config/types.scala:92-93, 150`) and is set from no HOCON key and no CLI argument (it is absent from `SharedConfigReader`, `CliMethod`, and `TessellationIOApp`). There is no `consensus { local-health-monitor { ... } }` block in any `*.conf`.

**Operator impact:** the self-health throttle's documented MVP stop-gap -- pinning `operatorOverride = Critical` to deprioritize a known-bad community peer in leader selection without a restart -- is **not usable in the field today**. `operatorOverride` is inert until wired. The thresholds are NOT in `deterministicConfigHash` today (the hash string only covers `ConsensusConfig` fields, and `LocalHealthMonitorConfig` lives in `SharedConfig`, not `ConsensusConfig`); the scaladoc design intent (`config/types.scala:77-80`) is that the thresholds *must* enter the hash if ever exposed, because divergent thresholds would compute different self-health hints and fork. So if these are ever wired via HOCON they will be consensus-critical and must be set cluster-wide. There are no operator knobs for the local health monitor in this release.

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
2. Per-environment Global knobs (`max-facilitator-count`, `core-committee-size`, `quorum-shrink-activation-views`, `active-admission-min-probation-reentry-slots`, `active-admission-recent-signer-window`, `certified-consensus-activation-ordinal`) must match the complete Global cluster; **adding or removing an env entry is a consensus change** because absent means a specific resolved default. Currency has no v35 activation entry.
3. Changing any hashed knob (including the compiled-in `view-interval`) requires an all-or-nothing coordinated cold restart because divergent configs can derive and finalize different state. The advertised `versionHash` is only a release fence and does not certify runtime config equality; verify the effective `deterministicConfigHash` fleet-wide before launch. The v35 activation ordinal separately coordinates the certified state-machine boundary.
4. Genuinely operator-safe knobs are the non-consensus ones: snapshot-serving rate limits (`snapshot-serving { ... }`), storage paths, and the timing-only grace/round-duration knobs.
5. `LocalHealthMonitorConfig` has no operator knobs in this release; `operatorOverride` is inert. `CL_MPT_VERIFY_INCREMENTAL` and `CL_RAISE_ON_FOLLOWER_DIVERGENCE` are env-only diagnostics/safety toggles, both OFF by default.

---

## Source of truth

- Config schema and `deterministicConfigHash`: `modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/config/types.scala`
- Deployed values: `modules/dag-l0/src/main/resources/dag-l0.conf`
- Signature grace: `modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/infrastructure/consensus/SignatureGraceDecision.scala`
- Env toggles: `GlobalSnapshotAcceptanceManager.scala:1163-1186`, `GlobalSnapshotContextFunctions.scala:337-384`
