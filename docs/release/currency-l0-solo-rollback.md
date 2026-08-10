# Currency L0 rollback recovery: operator release note

This release adds an opt-in Currency L0 recovery command for a fully stopped
metagraph:

```text
currency-l0 run-rollback ... --allow-solo-consensus
```

The override lets one designated rollback node seed the next consensus outcome
with itself so it can produce the snapshots that returning validators need for
their observation window. The flag is off by default. Normal rollback continues
to preserve the checkpoint proof-signer committee and ordering.

## Operator-visible behavior change

Currency L0 now marks `run-validator` processes as validators at startup, as
DAG L0 already does. A validator whose temporary local view contains only
itself will no longer produce a competing solo history. Consequently, restarting
only one former validator is not a recovery procedure for a fully stopped
metagraph. Use the coordinated rollback procedure when that situation occurs.

This is an intentional fork-safety tradeoff. It does not change snapshot or
state-proof schemas, signed message schemas, deterministic configuration, or
behavior for a normally operating cluster.

## Required operational controls

> **DANGER — never persist `--allow-solo-consensus`.** Do not put it in a
> systemd unit, container entrypoint, deployment manifest, environment variable,
> or monitoring/automatic-restart command. It is a one-node, one-invocation
> outage-recovery override. Repeating it automatically on isolated nodes can
> create conflicting valid histories.

Before using it:

1. Confirm the metagraph is fully stopped and disable every automatic restart.
2. Deploy the same jar to all metagraph nodes.
3. Run rollback with the flag on exactly one designated producer.
4. Confirm that producer advances at least five ordinals.
5. Rejoin the remaining nodes one at a time with ordinary `run-validator`.
6. Remove the flag from the operator command and verify all persistent launch
   configurations remain flag-free before restoring monitoring.

See the full
[Currency L0 single-node rollback recovery runbook](../operations/currency-l0-solo-rollback.md)
for safety checks, metrics, committee-regrowth expectations, and stop
conditions.
