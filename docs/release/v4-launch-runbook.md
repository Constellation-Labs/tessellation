# v4 Launch Runbook: Coordinated Cold Restart

This runbook is the operator procedure for the v4 release on `release/testnet`. The launch is an
all-or-nothing **coordinated cold restart**: every node must come up on the same release artifact, with
the same per-environment consensus config and launch-gate ordinals, or peers refuse to handshake (or,
for values outside the handshake hash, silently fork). Restart is from a recent agreed **checkpoint** snapshot, never a genesis
replay. This document covers (1) why the cold restart is mandatory, (2) the gate-ordinal-setting
checklist an operator follows at deploy, and (3) the raw `sys.env` toggles that have no HOCON binding.

Ground truth is the code. Every claim below cites the source file and line it was verified against.

> **Update 2026-10-10 (ADR-0035 / #1627):** no network carries v4 history any more. testnet and
> integrationnet are fresh-genesised on v4.1 (every `fields-added-ordinals` threshold and
> `snapshot.certified-consensus-activation-ordinal` is `0`, like dev), so for them this is a genesis
> launch, not a checkpoint restart. Only mainnet replays signed v3.5 history; it enters v4.1 at a
> single cutover through the recovery-seed rollback described in section 2. Every v4.1-only gate was
> folded into `tessellation-41-migration`, so the per-gate checklist below collapses to two
> ordinals. See [ADR-0035](../adr/0035-v35-to-v41-mainnet-migration.md).

---

## 1. Why a coordinated cold restart is mandatory

The v4.1 jar has two distinct compatibility fingerprints. They must both be preserved and checked;
they do not replace one another:

- The hard join fence is `versionHash`: the current hasher hashes the embedded
  `BuildInfo.version`, unless `CL_VERSION_HASH` supplies an opaque literal override
  (`TessellationIOApp.scala:186-189`). Both directions of the join handshake compare this value and
  raise `VersionMismatch` on inequality (`domain/cluster/programs/Joining.scala:252`). Official
  release builds must therefore carry the intended, distinct release tag, and
  `CL_VERSION_HASH` should be unset.
- `consensusSchemaVersion` and the consensus knobs are folded into
  `deterministicConfigHash` (`config/types.scala`). L0 startup resolves the exact effective config,
  advertises its hash in `RegistrationRequest`, and `Joining` requires strict equality (including
  presence). Facilities carry the same hash for additional logs/metrics. Equality remains a
  mandatory operator preflight so bad settings fail before fragmenting a cold start.

The join handshake does **not** compare `RegistrationRequest.jar`. That field is advertised and
stored as peer metadata only. Operators must verify that every node runs the identical release
artifact because a different jar built with the same reported version is not rejected solely by
`RegistrationRequest.jar`.

**Consequences for the deploy:**

- **No rolling upgrade.** A distinctly tagged release partitions from the previous release at the
  Tessellation version-hash join fence. The upgrade is all-or-nothing. This check applies at join
  time; it does not evict an already-connected old process, so the entire fleet must be stopped
  before any new process starts.
- **Restart from a checkpoint, not genesis (mainnet).** Genesis replay is never performed on mainnet;
  testnet and integrationnet start the v4.1 line from a fresh genesis. The cluster restarts
  from a recent agreed snapshot ordinal that all source/priority nodes hold on disk. Already-signed
  history is preserved; the new jar must re-derive it byte-identically (this is what the ordinal gates in
  section 2 guarantee).

### Deploy sequence

1. Disable automated restart/rollback actions. Before stopping anything, create
   the timestamped evidence bundle required by
   [`RELEASE_POLICY.md`](RELEASE_POLICY.md#mandatory-pre-stop-evidence-and-monitoring-gate).
   Capture active and rotated application/HTTP logs; the system journal,
   service and exit status; any existing heap/core/JVM crash artifact; redacted
   environment, effective configuration, launch/unit/deployment definitions;
   jar, version, `versionHash`, `deterministicConfigHash`, and schema-version
   identifiers; the chosen anchor and Snapshot Streaming observations; and
   manifests of snapshot indexes, locks/journals, and sidecars. Source log
   retention can be less than 24 hours under load, so preserve this bundle
   outside live rotation directories as durable release evidence.
2. Quiesce the network and request an orderly stop on every peer. Because the
   version-hash fence applies only when a peer joins, there is no safe overlap
   window; all nodes must be down before any new-jar node comes up. Escalate an
   unresponsive JVM to a hard kill only after the evidence capture, and record
   its stop timeout, final service status, signal, and exit status in the bundle.
3. Confirm the launch jar is staged on every node and that the gate-ordinal checklist (section 2) has
   been completed in the jar-packaged config before assembly.
4. Bring up the source / priority peers first. The priority set is configured under `priority-peer-ids`
   in `application.conf`. `Ready` confirms only node lifecycle progress; it
   does not release the monitoring gate.
5. Bring up the remaining peers. They register against the priority peers; matching version hashes
   admit them. Independently verify the deterministic config hash is byte-identical fleet-wide.
6. While `dag_consensus_normal_first_round_alignment_held == 1`, a flat tip is
   intentional synchronization and is a **DO-NOT-RESTART** condition. Monitoring
   may alert, but automated stop/restart/rollback actions remain inhibited.
7. Re-enable those actions only after the canonical first successor is accepted
   and `dag_consensus_signing_finality_audit_current_finality_margin > 0`. A
   `Ready` source set alone is insufficient. If the restart uses
   `CL_GL0_RECOVERY_SEED_COMMITTEE`, apply the stronger recovery gates: remove
   the environment from every selected source, wait through canonical `R+2` and
   public durability at/after v35, and verify Snapshot Streaming lineage
   alignment before enabling automation.

---

## 2. Gate-ordinal-setting checklist (FieldsAddedOrdinals)

New or changed deterministic behaviour is gated behind a per-environment activation ordinal so that
already-signed history re-derives byte-identically. The mechanism is `FieldsAddedOrdinals`
(`config/types.scala`), loaded from the `fields-added-ordinals` HOCON block in `application.conf`.
These values are literals packaged into the assembly jar's `application.conf`; there are no
environment-variable overrides for `fields-added-ordinals`. They must be finalized before assembly,
and changing one requires a coordinated artifact redeploy rather than a runtime config update.
Both L0 applications fold every resolved threshold, the dust-sweep schedule and the derived
state-proof/staking boundaries into `deterministicConfigHash` (`SnapshotOrdinalConfig.ordinalConfigHashFor`,
`config/types.scala`), so a peer whose resolved values differ is rejected at join. A unanimously wrong
ordinal is not detected; `RegistrationRequest.jar` is not compared.

> **The cardinal rule, stated once:** an ordinal gate must be set so the chain crosses it **only after**
> the new jar is live cluster-wide. A too-early crossing on the old jar misses the gated behaviour. For
> the dust sweep specifically, a missed sweep is not re-attempted until a rollback re-crosses the ordinal
> (the `dust-sweeps` comment in `application.conf`).

A placeholder value of **9999999** means "keep the OLD path until that finite ordinal is reached". Leaving
a placeholder in place at launch silently keeps the pre-fix behaviour active.

### Checklist

testnet, integrationnet and dev need no gate edits: every threshold is `0` and
`FieldsAddedOrdinalsSuite` asserts it. For mainnet, every v4.1-only replay/state-transition rule is
selected by ONE cutover gate C, `fields-added-ordinals.tessellation-41-migration`. The former
separate gates `sub-trie-roots`, `fee-transaction-security`, `currency-snapshot-protocol-v1`,
`fixing-delegated-stake-double-withdrawal` and `fixing-spend-action-aggregate-balance` are folded
into it (their `...For` accessors resolve to C); `last-legacy-state-proof-ordinal` and
`incremental-delegated-staking-starting-ordinal` are no longer keys and are derived as `C - 1`. The
gates `sc-fee-balance-from-context`, `set-sum-fix` and `delegated-rewards-full-committee` were deleted,
and no dust sweep is scheduled on any network (`dust-sweeps {}`).

Let R be the final v3.5 global snapshot ordinal (the rollback anchor). Set exactly two values, both
`9999999` placeholders today:

- [ ] **`snapshot.certified-consensus-activation-ordinal.mainnet = R`** (`dag-l0.conf`). Exact key;
      it must be <= the rollback anchor (recovery-seed startup rejects an anchor below activation,
      `Main.validateRollbackAnchorAtOrAfterActivation`). This build has no pre-v35 Global L0 engine: a
      node refuses to produce a round below activation
      (`GlobalSnapshotConsensusStateCreator.CertifiedConsensusNotActiveForProduction`) and refuses to
      start without an activation entry for its environment.
- [ ] **`fields-added-ordinals.tessellation-41-migration.mainnet = R + 1`** (node-shared
      `application.conf`). R itself replays with v3.5 rules; R + 1 is the first v4.1 snapshot.
- [ ] **Sanity-check the historical gates.** Every other mainnet value in `fields-added-ordinals`
      (`tessellation-3-migration` through `fixing-global-allow-spend-expiration`, plus
      `removing-processed-delegated-stake-withdrawals` = `6176655`) records where a rule entered signed
      mainnet history. Confirm they are unchanged from the in-tree values; an accidental edit changes
      replay at that boundary and forks.

### Mainnet cutover procedure (recovery seed)

From the `certified-consensus-activation-ordinal` comment in `dag-l0.conf`:

1. Halt v3.5 and confirm the source nodes share tip R and its hash.
2. Start the lead source node with `run-rollback --rollback-hash H(R)` and the other sources with
   `run-validator`, all with `CL_GL0_RECOVERY_SEED_COMMITTEE` set to the source-node committee.
3. Wait until R + 1 is certified, R + 2 carries its QC and
   `dag_consensus_recovery_seed_boundary_publicly_durable == 1`.
4. Unset `CL_GL0_RECOVERY_SEED_COMMITTEE` on the sources (it re-arms on every fresh JVM).
5. Community validators re-join through ordinary download (validated from the public recovery root)
   and certified open admission; they do not sign or earn until admitted.

After editing, the values are compiled into the assembly. Re-assemble the jar and independently
verify that the identical artifact digest is staged everywhere; the join handshake does not perform
that digest comparison.

---

## 3. Environment toggles with no HOCON binding

Two operational toggles are read directly via `sys.env.get` with **no HOCON key and no `CL_*`
counterpart in any `.conf` file**. They are invisible to an operator grepping `application.conf` or
`dag-l0.conf`, and they take effect **per process via the environment**, not via config reload. Neither
is in `deterministicConfigHash`, so they may differ per node without forking (the divergence toggle still
has availability consequences, see below).

| Env var | Default | What it does | When to enable |
|---------|---------|--------------|----------------|
| `CL_MPT_VERIFY_INCREMENTAL` | off | On the incremental acceptance path, independently rebuilds the full MPT root from the swept GSI in a standalone trie (does not touch the shared store) and compares it to the incremental store root. Log-only: logs `[MPT.VERIFY] ... INCREMENTAL DRIFT detected` on mismatch and swallows any rebuild error. Never affects acceptance. Skipped on the sweep ordinal itself (`didSweep`). (`GlobalSnapshotAcceptanceManager.scala:1163-1186`) | During an MPT divergence hunt, to catch incremental-vs-canonical drift at the exact ordinal it is introduced. Safe to leave on; it only costs an extra rebuild per ordinal. |
| `CL_RAISE_ON_FOLLOWER_DIVERGENCE` | off | When a follower (currency-l0, dag-l1, currency-l1) or a dag-l0 recovery/replay re-runs acceptance over an already-L0-validated snapshot and its local reconstruction rejects items the signed snapshot included, it has diverged. By default this logs a single loud `[FOLLOWER-STATE-DIVERGENCE]` line and **continues** (trusting the L0 majority, re-syncing via the caller's download path). With this set to `true` it instead raises `GlobalStateDivergenceError` and halts so the caller's recovery re-syncs the correct state. (`GlobalSnapshotContextFunctions.scala:337-352`, `:378-384`) | Only on followers / recovery paths where a hard halt-and-resync is preferable to proceeding on possibly-forked state. The availability tradeoff: enabling it converts a warn-and-continue into a stop, so a node that would have caught up via download instead halts until re-synced. Leave off on nodes where availability is preferred over a strict divergence stop. |

Both compare case-insensitively against `"true"` (`GlobalSnapshotAcceptanceManager.scala:1186`,
`GlobalSnapshotContextFunctions.scala:351`).

---

## See also

- `docs/release/RELEASE_POLICY.md` -- stage gating; ordinal feature-flag config surface.
- `docs/consensus/README.md` -- consensus mechanism reference (FSM, declarations, facilitator selection,
  `deterministicConfigHash` fold).
- Source of truth for the gates: `FieldsAddedOrdinals` in `config/types.scala` and the `fields-added-ordinals`
  block in `application.conf`; cutover detail in `docs/operations/fields-added-ordinals.md`.
