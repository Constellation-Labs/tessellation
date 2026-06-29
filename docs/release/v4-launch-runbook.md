# v4 Launch Runbook: Coordinated Cold Restart

This runbook is the operator procedure for the v4 release on `release/testnet`. The launch is an
all-or-nothing **coordinated cold restart**: every node must come up on the same jar, with the same
per-environment consensus config, and with the launch-gate ordinals set, or peers refuse to handshake
(or worse, silently fork). Restart is from a recent agreed **checkpoint** snapshot, never a genesis
replay. This document covers (1) why the cold restart is mandatory, (2) the gate-ordinal-setting
checklist an operator follows at deploy, and (3) the raw `sys.env` toggles that have no HOCON binding.

Ground truth is the code. Every claim below cites the source file and line it was verified against.

---

## 1. Why a coordinated cold restart is mandatory

The v4 jar bumps `consensusSchemaVersion` to **33** (`config/types.scala:830`) and folds several dozen
consensus knobs plus the schema version into a single `deterministicConfigHash`
(`config/types.scala:950-1044`). That hash is a handshake fence:

- A joining peer sends its `consensusConfigHash` in its registration request. When both sides provide
  one, the acceptor compares them and raises `ConsensusConfigMismatch` if they differ; the check is
  skipped only when one side omits the hash (`domain/cluster/programs/Joining.scala:303-307`). On an
  all-new-jar launch both sides set it, so a node whose config hash diverges cannot join.
- Every `Facility` declaration also carries `consensusConfigHash: Option[Hash]` as a per-round, peer-side
  fence (the Facility field is documented in `docs/consensus/README.md` section 6; the schema-version is
  folded into the hash at `config/types.scala:1042-1043`). A peer on a divergent config is rejected at the
  Facility stage even if it slipped past registration.

The jar hash is the primary fence (peers only connect to matching jar hashes); the bumped
`consensusSchemaVersion` and the config-hash fold are the explicit secondary fences. The `dag-l0.conf`
comment states this directly: "Coordinated cold restart required because this value is in
`deterministicConfigHash` (and `consensusSchemaVersion` is bumped as a fence)"
(`dag-l0.conf:68-69`).

**Consequences for the deploy:**

- **No rolling upgrade.** A mixed-version cluster partitions: old-jar and new-jar peers compute
  different `deterministicConfigHash` values and reject each other at registration and at Facility. The
  upgrade is all-or-nothing.
- **Restart from a checkpoint, not genesis.** Genesis replay is never performed. The cluster restarts
  from a recent agreed snapshot ordinal that all source/priority nodes hold on disk. Already-signed
  history is preserved; the new jar must re-derive it byte-identically (this is what the ordinal gates in
  section 2 guarantee).

### Deploy sequence

1. Quiesce the network and **hard-kill the JVMs on every peer**. Because the config-hash fence partitions
   mixed-version peers, there is no safe overlap window; all nodes must be down before any new-jar node
   comes up.
2. Confirm the launch jar is staged on every node and that the gate-ordinal checklist (section 2) has
   been completed in the jar-packaged config before assembly.
3. Bring up the source / priority peers first. The priority set is configured under `priority-peer-ids`
   in `application.conf:129`. Confirm each reaches `Ready` before proceeding.
4. Bring up the remaining peers. They register against the priority peers; matching jar hash and
   `consensusConfigHash` admit them, divergent ones are rejected.
5. Only after the source/priority peers are confirmed `Ready` should auto-restart monitoring (the
   auto-restart lambda referenced in `RELEASE_POLICY.md`) be re-enabled, so a node still mid-handshake is
   not force-cycled.

---

## 2. Gate-ordinal-setting checklist (FieldsAddedOrdinals)

New or changed deterministic behaviour is gated behind a per-environment activation ordinal so that
already-signed history re-derives byte-identically. The mechanism is `FieldsAddedOrdinals`
(`config/types.scala:27-48`), loaded from the `fields-added-ordinals` HOCON block
(`application.conf:210-293`). These values are packaged into the assembly jar's `application.conf`, so
the **jar hash plus the environment is the determinism fence** (`config/types.scala:43-46`). Changing any
of them is therefore itself a coordinated jar redeploy, not a config reload.

> **The cardinal rule, stated once:** an ordinal gate must be set so the chain crosses it **only after**
> the new jar is live cluster-wide. A too-early crossing on the old jar misses the gated behaviour. For
> the dust sweep specifically, a missed sweep is not re-attempted until a rollback re-crosses the ordinal
> (`application.conf:283-285`).

A placeholder value of **9999999** means "keep the OLD path / never fire on this environment yet". Leaving
a placeholder in place at launch silently keeps the pre-fix behaviour active.

### Checklist

Work through every `fields-added-ordinals` sub-key whose mainnet (or target-environment) value is still a
placeholder. For each, decide the launch-checkpoint ordinal and set it in the jar-packaged
`application.conf` before assembly.

- [ ] **`sc-fee-balance-from-context`** (`application.conf:275-280`, `config/types.scala:42`). At/after
      this ordinal the state-channel fee-affordability check reads the metagraph owner balance from the
      deterministic `accept()` context (`lastGlobalSnapshotInfo.balances`); below it from the pre-fix
      `mptStore.getBalance` path, so signed history re-derives byte-identically
      (`GlobalSnapshotStateChannelEventsProcessor.scala:324-328`). The ordinal is resolved at
      `GlobalSnapshotConsensus.scala:152` and `SharedServices.scala:195`, both **failing closed** to
      `SnapshotOrdinal.MaxValue` when the environment has no entry (the gate never fires, so an unset env
      keeps the OLD path). **mainnet and integrationnet are `9999999` placeholders (both still on the
      `mptStore` path); testnet is pinned to `3101393`, its real v4.0.0->alpha.0 cutover.** SET mainnet
      and integrationnet to the coordinated context-deploy ordinal at deploy. Leaving an env unset
      keeps the pre-fix `mptStore` balance source.

- [ ] **`sub-trie-roots`** (`application.conf:285-294`, `config/types.scala:43-46`). At/after this
      ordinal, MPT-format `GlobalSnapshotStateProof` carries per-`GlobalStateFieldId` roots in addition
      to the overall `mptRoot`, making state-root divergence field-localizable. This changes signed proof
      bytes, so mainnet/testnet/integrationnet are `9999999` placeholders until each network deliberately
      activates the proof shape at a coordinated cold-restart ordinal. For a restart checkpoint `N`, use
      `N + 1`.

- [ ] **`dust-sweeps`** (`application.conf:295-301`, `config/types.scala:47-65`). Per-environment,
      keyed by the exact ordinal each one-time GSI dust sweep fires at. Only `testnet` has an entry today
      (`3154700` with `threshold: 100000`); there is **no mainnet entry**. If a sweep is part of the
      launch, add the environment's entry and **finalize the ordinal right before deploy** so it is one
      the chain reaches only after the deflating jar is live cluster-wide (`application.conf:283-285`).
      The sweep is consensus-critical: every node must compute the identical swept GSI and MPT root at the
      sweep ordinal (`GlobalSnapshotDustSweep.scala:16-26`). Burn = omit `collection-address`; credit a
      treasury = supply `collection-address: "DAG..."`. `threshold` is in datum.

- [ ] **`set-sum-fix`** (`application.conf:265-270`, `config/types.scala:37`). mainnet, testnet, and
      integrationnet are all the placeholder `9999999` (only `dev` is `0`, so the gate is not yet active on
      any live environment). Set to the launch checkpoint ordinal only if this fix is part of the launch;
      otherwise leave the placeholder so the old path stays active.

- [ ] **`fixing-allow-spend-and-token-lock-validation`** (`application.conf:259-264`,
      `config/types.scala:36`). mainnet is already a real ordinal (`5058096`); **testnet and
      integrationnet are the placeholder `9999999`.** Set the testnet/intnet ordinals to the launch
      checkpoint if this validation fix is part of the launch on those environments.

- [ ] **Sanity-check the historical gates** (`application.conf:211-258`). The migration gates above this
      block (`tessellation-3-migration`, `tessellation-301-migration`, `check-sync-global-snapshot-field`,
      `metagraph-sync-data`, the two `updated-last-sync-*`, `updating-combine-function-spend-actions`,
      `fixing-allow-spend-expiration`) already carry real per-environment ordinals. Confirm they are
      unchanged from the in-tree values for the environment being launched; an accidental edit changes
      artifact bytes at that boundary and forks.

After editing, the values are compiled into the assembly. Re-assemble the jar, and treat the new jar hash
as the deploy artifact for section 1.

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
- Source of truth for the gates: `config/types.scala:27-48` and `application.conf:210-293`.
