# V35 certified-consensus rollout

This runbook accompanies [ADR-0032](../adr/0032-certified-consensus-outcomes.md).

## Scheduled activations

- No public activation is currently scheduled. The former IntegrationNet DAG L0 key at
  `5,890,500` was withdrawn before activation after the rc.5/rc.6 incidents. A replacement
  key must be selected and announced only after the rc.7-compatible dormant path has
  soaked successfully.
- Every Currency L0 remains disabled here. Each metagraph has an independent ordinal
  space and must select and coordinate its own activation key. Mainnet, IntegrationNet,
  and testnet remain disabled for both layers.

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
   For a non-genesis DAG activation, the canonical signed seed and the final roster
   after seedlist, collateral, selector, and projector processing must each contain at
   least two members, and all current members together must be able to satisfy the
   configured `Q(N+1)` next-seat headroom. Under supermajority a participating pair can
   grow; under unanimity no finite `N` can prove an unseated `(N+1)`th signer, so a
   non-genesis certified activation in unanimity mode fails closed. The exact activation
   never falls back to local `self`. An activation configured at or before the first facilitated key is genesis
   mode: there is no legacy-to-certified exact-key transition, and intentional
   single-node development genesis remains supported.
4. Prove that the signed activation seed is live: its observed parent signers must meet
   the frozen-committee finality floor, and any planned admission batch must satisfy
   `observed parent signers >= Q(seed size + batch size)`. V35 enforces this headroom
   even while its freshly reset legacy proof-size window still reports bootstrap.
5. Verify snapshot/state-proof golden fixtures and v34 pre-activation declaration
   fixtures.
6. Exercise activation from deliberately divergent legacy local sidecars and verify
   that nodes derive one frozen committee and one ProposalValue hash.
7. Exercise a view change, a carried QC, same-key certified outcome recovery, process
   restart, and coordinated rollback in staging. Kill a process after its prepare vote
   and again after QC formation; after restart, verify that the journal refuses a
   conflicting vote and that the first VCV/timeout vote carries the verified restored
   QC. Also kill during the journal write and during the certified-outcome sidecar
   write: no vote/commit may progress from a non-durable lock, and the lock must remain
   until the crash-atomic sidecar write has succeeded. A deliberately truncated journal
   file must fail closed rather than start consensus.
8. Exercise both Tier-1 and Core atomic replacement: three consecutive elapsed proof
   misses, Core-quorum Silent and ReadyAtTip certificates, one ProposalQC carrying equal
   admitted/evicted sets, unchanged committee cardinality, and no standalone ECS acceptance.
9. Load test broad Core+Tier-1 signing and record round-duration p50/p95, ProposalQC
   and CoreCommitQC formation, artifact proof margin, view changes, reward breadth, and
   `dag_consensus_outcome_hook_duration_seconds` p95.

## Deployment sequence

1. Choose a future activation key independently for each L0 cluster.
2. Stop the complete active cluster. Archive snapshots, certified-outcome sidecars,
   certified-vote-lock journals, configuration, and logs; verify a coherent
   pre-activation checkpoint.
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

Before activation, verify the ordinary-download lineage boundary on every source node:

- the exact activation outcome validates from the locally stored, state-proof-checked
  A-1 snapshot;
- a restart after activation retains both the current and immediately preceding
  certified outcome sidecars and validates the current outcome from that predecessor;
- a missing/corrupt predecessor fails before application storage, consensus storage,
  safety locks, or sidecars change; and
- a fresh post-activation node has a signed operator recovery plan or separately
  announced trusted checkpoint. Until contiguous certificate-chain download exists,
  it cannot securely bootstrap from one arbitrary Ready peer.

The `certifiedVoteLocks` directory is pre-finalization safety state. Do not remove it
to clear a stalled round, and do not treat a decode error as a missing cache. Ordinary
same-key retries retain the record. A certified finalization removes it only after the
matching complete-outcome sidecar write succeeds; rollback/download initialization
have deliberately different semantics. Download/restart removes finalized or stale
records at or below its accepted ordinal and retains a possible next-key lock. Only an
explicit coordinated rollback prunes records above its accepted ordinal.

No restart is required merely because the ordinal crosses; the aligned nodes switch
deterministically at the configured key.

## Availability and rollback

V35 does not shrink the current-round safety universe. If more than one third of the
frozen Core disappears during a round, a coordinated restart may be required.

Global L0 replacement is preventive, not subquorum recovery. It runs only while the
original frozen Core can still certify the complete N-to-N transition. Operators must
alert on any proposal where admitted and evicted counts differ; honest nodes reject it.

If activation fails:

1. Stop the full cluster; do not let two partial histories race.
2. Archive logs and sidecars before changing anything.
3. Restore the verified pre-activation checkpoint and the prior coherent jar/config.
4. Move the activation key only through another announced, full-cluster rollout.

Do not work around `trusted_predecessor_sidecar_missing` by copying a peer's JSON
sidecar into the local directory. Local provenance is the authority: the predecessor
must have been produced by this node, accepted through the certified preflight, or
superseded by an explicit signed recovery plan. Copying unverified transport bytes
reintroduces the circular committee proof this boundary is designed to reject.

For Currency L0 emergency solo rollback, `--allow-solo-consensus` remains a one-shot,
exactly-one-node recovery operation. Never persist it in systemd or automatic restart
configuration.
