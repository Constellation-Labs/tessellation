# Currency L0 controlled rollback recovery

Currency L0 uses the flat synchronous protocol described in
[Currency L0 synchronous consensus](../consensus/currency-l0-synchronous.md). Its
recovery topology is the stable permissioned-metagraph topology:

- exactly one operator-controlled node starts `run-rollback`;
- every other node starts `run-validator`; and
- the rollback lead starts the new live committee with `{self}` while validators
  register and join through completed synchronous outcomes.

The rollback checkpoint's proof signers authenticate the historical artifact. They do
not become the post-rollback live committee merely because they signed that artifact.
The same self-only bootstrap rule applies with or without `--allow-solo-consensus`.

`--allow-solo-consensus` is retained as a compatibility-named, opt-in control for one
additional operation: it arms the deterministic-history publication refresh used to
resurrect a dormant Currency lineage. It does **not** select the facilitator set, lower
a quorum, enable a Core/Tier bypass, or switch Currency L0 into certified consensus.

> **DANGER — exactly one rollback lead.** Never run independent rollback leads, with
> or without this flag. Two isolated leads can create two internally valid Currency
> histories. Stop the complete Currency cohort, disable automatic restart/rollback,
> select one canonical checkpoint, and start exactly one controlled rollback lead.

> **DANGER — never persist `--allow-solo-consensus`.** Do not add the flag to a
> systemd unit, container entrypoint, deployment manifest, environment variable, or
> monitoring command. It authorizes a new deterministic-history refresh on every
> external invocation. Use it for one manually coordinated dormant-lineage recovery,
> then verify that all persistent launch commands are flag-free before restoring
> automation.

## When to use the flag

Omit `--allow-solo-consensus` for an ordinary rollback that does not need to replace or
refresh dormant `GlobalSnapshotSync` history. The ordinary lead is already self-only and
can produce the public successors needed by validators.

Use the flag only when the operator has established that a dormant Currency lineage needs
the signed protocol-v1 recovery publication described in
[Currency L0 deterministic history and dormant-lineage resurrection](currency-l0-dormant-resurrection.md).
The flag tells the controlled lead to:

1. refresh its canonical Global L0 anchor and retained window after rollback;
2. construct one signed `GlobalSnapshotSync` refresh;
3. require that exact event in the first Currency successor;
4. retain and republish the exact signed Currency binary until canonical Global L0
   confirms it; and
5. fail closed if the activation, retained-window, unapplied-history, signer, or
   canonical-anchor checks do not hold.

If the inherited signed sync view contains only the lead, the refresh chains normally.
If it contains other peers, the MinValue-parent dormant-lineage reset is permitted only
at or after the announced Currency snapshot protocol-v1 boundary. The operational flag
authorizes emission; Currency and Global L0 validators independently validate the reset
from signed and consensus-carried inputs.

## Why validators can rejoin

The rollback/genesis lead starts with a flat one-member facilitator list by design. It
does not depend on Global L0's Core/Tier-1 committee, ProposalQC, admission certificate,
view-change, or timeout-certificate machinery.

Returning validators recover as follows:

1. The lead produces the public successors needed by the Currency download observation
   window.
2. A validator downloads and validates public Currency history, observes four sequential
   successors, and requests the exact private outcome from the artifact-proof signers.
3. The validator verifies the public artifact, exact proof envelope, context, flat
   committee, and registration authorization before installing the outcome.
4. Registrations are advertised in the complete Facility phase. The finished synchronous
   outcome carries a bounded candidate set, and the candidate enters the next round's
   flat facilitator list.
5. Every retained facilitator must complete the artifact- and binary-signature phases for
   the new outcome. There is no quorum-certified Currency admission path.

A singleton can carry at most two candidates so the normal three-member metagraph shape
can form. For `R >= 2` retained incumbents, one outcome carries at most `R - 1`
candidates. The configured flat `max-facilitator-count` (shipped value `20`) supplies an
admission cap and never ejects an incumbent. A deterministic cursor rotates the eligible
registration set.

If both singleton candidates fail before their first Facility, the synchronous ACK rules
cannot safely invent a replacement authority set; controlled recovery is required. Do
not interpret cluster `Ready` state alone as consensus membership. Verify the Currency
outcome facilitator list and actual snapshot proofs.

## Safety and compatibility contract

- Rollback and genesis seed `{self}` regardless of `--allow-solo-consensus`.
- The flag changes only recovery-publication behavior and defaults to false.
- The flag itself adds no snapshot field, state-proof field, hash construction, or
  Currency-local v35 activation key.
- Currency L0 remains on the flat synchronous protocol. It has no Core/Tier roles,
  ProposalQC, certified admission, certified Currency lineage, or v35 pacemaker.
- Currency snapshot protocol `1.0.0` is a separate deterministic-history transition. Its
  existing signed `CurrencyIncrementalSnapshot.version` changes at the announced **Global
  L0 ordinal** from ADR-0033; the jar's SemVer is not stamped into the chain.
- Runtime flags are not connection-handshake inputs. Operational control of the one
  rollback lead is therefore a load-bearing safety boundary.
- Deploy one immutable compatible version to the complete metagraph cohort before
  recovery. Mixed Currency consensus versions are unsupported.
- All public networks are in the JSON era. New recovery functionality has no Kryo
  fallback.

Currency `run-validator` startup sets validator mode. A validator whose temporary local
view contains only itself must follow public history and cannot act as an independent
rollback/genesis authority. Rollback/genesis startup remains the only controlled lead
path.

## Coordinated recovery runbook

### Preconditions

1. Confirm the complete Currency cohort is stopped. Disable systemd, container, and
   monitoring restart/rollback automation.
2. Record the canonical Currency checkpoint ordinal, artifact hash, binary hash, proof
   population, and corresponding Global L0 state-channel hash. Do not choose an anchor by
   recency alone.
3. Confirm no other node is running rollback or producing Currency history from that
   checkpoint.
4. Deploy the same release, advertised version, and effective configuration to every
   Currency node.
5. Select one stable node as the rollback lead. Every other node remains stopped until
   the lead produces the required public observation window.
6. For a dormant-lineage refresh, additionally verify the activation ordinal, inherited
   sync view, canonical recent Global L0 anchor, empty applicable
   `unappliedGlobalChangeOrdinals`, seedlist/allowance-list eligibility, and available
   retained-window headroom. Drain previously submitted state-channel work before
   authorizing replacement.

### Bootstrap the lead

1. Start the selected node with ordinary `run-rollback` arguments. Add
   `--allow-solo-consensus` only for the verified dormant-lineage refresh case.
2. Confirm:
   - `dag_consensus_rollback_bootstrap_total{mode="controlled_rollback_lead"}`
     increments;
   - `dag_consensus_rollback_proof_signer_count` reports the checkpoint proof count; and
   - `dag_consensus_rollback_bootstrap_facilitator_count` is `1`.
3. Verify the Currency outcome facilitator list and produced proof set name only the lead.
4. For an armed refresh, require
   `RECOVERY_SYNC_REFRESH_ENQUEUED`,
   `dag_currency_l0_recovery_sync_construction_guard_armed == 1`, and
   `dag_currency_l0_recovery_sync_refresh_pending == 1` before the first successor.
5. After the first local successor, the construction guard must become zero while the
   publication remains pending. Require the exact recovery binary to appear in canonical
   Global L0 and `refresh_pending` to become zero before the retained-window deadline.
6. Let the lead produce at least four additional public Currency successors so the first
   validator can complete its observation window.

Stop and investigate if a second node reports rollback bootstrap, Global L0 shows a
competing Currency binary, the lead cannot advance, the first successor omits the armed
refresh, or retained-window headroom approaches expiry. Do not edit the outbox receipt or
restart Global L0 to extend the deadline.

### Rejoin validators

1. Start node 2 with ordinary `run-validator` and no solo flag.
2. Require public download, four-successor observation, a successful exact-outcome
   corroboration, and an actual Currency proof from node 2. Do not infer success from
   cluster `Ready` alone.
3. Start each additional validator one at a time and repeat the same checks.
4. Confirm the facilitator list, artifact proofs, and binary proofs contain the intended
   cohort and ordinals continue advancing at the expected cadence.
5. Verify every persistent launch command is free of `--allow-solo-consensus`, preserve
   the recovery evidence, and only then restore monitoring and restart automation.

## Primary recovery signals

| Signal | Required interpretation |
|---|---|
| `dag_consensus_rollback_bootstrap_total{mode="controlled_rollback_lead"}` | one controlled rollback bootstrap; more than one lead is an incident |
| `dag_consensus_rollback_bootstrap_facilitator_count` | `1` for Currency rollback/genesis bootstrap |
| `dag_currency_consensus_outcome_corroboration_total{outcome}` | validator handoff result; require `success` before counting re-entry |
| `dag_currency_consensus_self_excluded_total` | node committed a common result that excludes it and re-entered download |
| `dag_currency_consensus_peer_ahead_reanchor_total` | stale local attempt abandoned only after frozen-authority evidence of a newer public outcome |
| `dag_currency_l0_recovery_sync_construction_guard_armed{mode}` | armed event still required in the first successor; do not restart or add validators |
| `dag_currency_l0_recovery_sync_refresh_pending{mode}` | exact recovery binary is not yet canonically confirmed |
| `dag_currency_l0_recovery_sync_selected_target_remaining_ordinals` | retained-window deadline; alert at `<= 5` while pending |

## Source anchors

- Rollback/genesis self-only topology and rollback flag handling:
  [`CurrencyL0App.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/CurrencyL0App.scala).
- CLI compatibility flag:
  [`method.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/cli/method.scala).
- Flat synchronous phases and bounded candidate selection:
  [`CurrencySnapshotConsensusStateAdvancer.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/snapshot/CurrencySnapshotConsensusStateAdvancer.scala).
- Download observation and exact-outcome handoff:
  [`Download.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/snapshot/programs/Download.scala)
  and
  [`ConsensusManager.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/snapshot/synchronous/ConsensusManager.scala).
- Fixed-universe ACK removal:
  [`UnlockConsensusUpdate.scala`](../../modules/currency-l0/src/main/scala/io/constellationnetwork/currency/l0/snapshot/synchronous/update/UnlockConsensusUpdate.scala).
