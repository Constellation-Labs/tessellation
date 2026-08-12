# V35 certified-consensus rollout

This runbook accompanies [ADR-0032](../adr/0032-certified-consensus-outcomes.md).

## Scheduled activations

- IntegrationNet DAG L0: ordinal `5,890,500`. It was selected on 2026-08-12 at a
  highest observed priority-node tip of `5,883,109`. At the previously measured healthy
  cadence of approximately 45.9 seconds per ordinal, the estimated crossing is
  2026-08-16 around 19:00 UTC. Stalls, restarts, rollback, or transaction-driven event
  rounds can move that wall-clock estimate, so monitor the remaining ordinal distance.
- IntegrationNet Currency L0 remains disabled here. Every metagraph has an independent
  ordinal space and must select and coordinate its own activation key. Mainnet and
  testnet remain disabled for both layers.

## Compatibility boundaries

- `consensusSchemaVersion=35` is an immediate active-cluster compatibility fence.
- `certified-consensus-activation-ordinal` is the deterministic behavior boundary.
- DAG L0 and every Currency L0 use their own snapshot ordinal space.
- The public global/currency snapshot and state-proof schemas do not change.

Do not confuse the two gates. Nodes started with different schema/config hashes cannot
form a healthy active consensus cluster even below the activation key. Conversely,
deploying the aligned v35 jar with no public activation entry leaves the v35 behavior
dormant.

## Before selecting an activation key

1. Build one commit and record its tag, commit, assembly checksums, resolved
   `consensusSchemaVersion`, deterministic config hash, and environment-specific
   activation key.
2. Confirm that the target network has enough lead time to announce the activation.
3. For DAG L0 and each Currency L0, confirm that the last legacy artifact contains
   signed controller evidence from which its activation committee can be seeded. Both
   layers intentionally fail closed when this evidence is absent; DAG
   `nextFacilitators` is not a valid substitute.
4. Verify snapshot/state-proof golden fixtures and v34 pre-activation declaration
   fixtures.
5. Exercise activation from deliberately divergent legacy local sidecars and verify
   that nodes derive one frozen committee and one ProposalValue hash.
6. Exercise a view change, a carried QC, same-key certified outcome recovery, process
   restart, and coordinated rollback in staging.
7. Load test broad Core+Tier-1 signing and record round-duration p50/p95, ProposalQC
   and CoreCommitQC formation, artifact proof margin, view changes, reward breadth, and
   `dag_consensus_outcome_hook_duration_seconds` p95.

## Deployment sequence

1. Choose a future activation key independently for each L0 cluster.
2. Stop the complete active cluster. Archive snapshots, certified-outcome sidecars,
   configuration, and logs; verify a coherent pre-activation checkpoint.
3. Install the same v35 assembly and the same resolved activation configuration on
   every active facilitator. Do not canary a mixed active consensus fleet.
4. Cold-start the cluster and verify identical deterministic config hashes and normal
   legacy progress below the key.
5. Before crossing, verify every expected active node is on the recorded jar/config.
6. At the key, verify the canonical legacy-window reset, frozen full/Core hashes,
   ProposalQC, CoreCommitQC, full artifact finality floor, persisted certified sidecar,
   and identical semantic value/derived operational outcome on multiple nodes. Raw
   sidecar files may contain different valid signature subsets and need not be
   byte-identical.
7. Continue watching admission/eviction cadence, signer/reward population, finality
   headroom, view changes, round duration, direct-send queue pressure, soft resets, and
   certified recovery. Include
   `dag_consensus_certified_recovery_total`,
   `dag_consensus_certified_recovery_candidate_total`, and
   `dag_consensus_outcome_sidecar_total`/`dag_consensus_outcome_hook_duration_seconds`
   in the activation dashboard.

No restart is required merely because the ordinal crosses; the aligned nodes switch
deterministically at the configured key.

## Availability and rollback

V35 does not shrink the current-round safety universe. If more than one third of the
frozen Core disappears during a round, a coordinated restart may be required.

If activation fails:

1. Stop the full cluster; do not let two partial histories race.
2. Archive logs and sidecars before changing anything.
3. Restore the verified pre-activation checkpoint and the prior coherent jar/config.
4. Move the activation key only through another announced, full-cluster rollout.

For Currency L0 emergency solo rollback, `--allow-solo-consensus` remains a one-shot,
exactly-one-node recovery operation. Never persist it in systemd or automatic restart
configuration.
