# Currency L0 controlled rollback: operator release note

Currency L0 now follows the stable flat synchronous recovery topology:

```text
one controlled node: run-rollback <canonical-checkpoint>
every other node:     run-validator
```

The rollback/genesis lead always seeds the live facilitator list with itself. Historical
checkpoint proofs authenticate the anchor but do not select the post-rollback committee.
Returning validators download public successors, validate an exact private outcome,
register, and enter through a completed synchronous outcome.

The compatibility-named flag:

```text
--allow-solo-consensus
```

does **not** enable the self-only committee. Rollback is self-only without it. The flag
only arms the one-shot deterministic-history publication/reset used to resurrect a
dormant Currency lineage at or after the announced Currency snapshot protocol-v1
boundary. Omit it for ordinary rollback.

Currency L0 does not use Global L0's Core/Tier committees, ProposalQC, certified
admission, or v35 pacemaker. This command adds no Currency-local v35 activation key and
does not stamp jar SemVer into Currency history.

## Required operational controls

> **DANGER — one rollback lead only.** Stop the complete metagraph, disable all
> automatic restart/rollback, choose one canonical checkpoint, and run exactly one
> controlled rollback lead. Two isolated leads can produce conflicting valid Currency
> histories.

> **DANGER — never persist `--allow-solo-consensus`.** Do not put it in a systemd
> unit, container entrypoint, deployment manifest, environment variable, or monitoring
> command. Every external invocation re-arms a deterministic-history refresh. Use it
> only for one reviewed dormant-lineage recovery, then verify every persistent command
> is flag-free before restoring automation.

For ordinary recovery:

1. Stop the complete Currency cohort and disable restart automation.
2. Deploy one immutable compatible version to every metagraph node.
3. Start exactly one rollback lead without the flag.
4. Require the lead to produce the four-successor public observation window.
5. Start validators one at a time with ordinary `run-validator`.
6. Verify actual Currency facilitator membership and artifact/binary proofs, not only
   cluster `Ready` state.

For dormant-lineage resurrection, add the flag only after completing the activation,
retained-window, unapplied-history, and canonical-anchor preflight. Do not add validators
until the exact first-successor recovery binary is canonically accepted by Global L0.

See the full
[Currency L0 controlled rollback recovery runbook](../operations/currency-l0-solo-rollback.md)
and the
[dormant-lineage resurrection runbook](../operations/currency-l0-dormant-resurrection.md)
for validation predicates, metrics, publication deadlines, and stop conditions.
