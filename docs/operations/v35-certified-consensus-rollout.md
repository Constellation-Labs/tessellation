# V35 certified-consensus rollout

This runbook accompanies [ADR-0032](../adr/0032-certified-consensus-outcomes.md) and
[ADR-0033](../adr/0033-versioned-currency-snapshot-history.md).

The public rollout also requires the independently released Snapshot Streaming artifact,
the complete metagraph application rebuild described in the
[metagraph upgrade guide](../release/metagraph-upgrade-guide.md), and an approved
[Snapshot Streaming and Block Explorer reconciliation mode](snapshot-streaming-block-explorer-reconciliation.md).
Passing this repository's tests does not satisfy those independent release gates.

## Scheduled activations

- IntegrationNet Global L0 v35 is scheduled for Global snapshot ordinal `5,923,000`
  in `v4.1.0-rc.13`, following the planned 2026-09-04 20:00 UTC coordinated cold
  restart. The former key at `5,890,500` was withdrawn before activation and must not
  be reused. The matching Snapshot Streaming release, activation rehearsal evidence,
  and final operator census remain independent go/no-go gates.
- V35 certification applies only to Global L0. Currency L0 uses its Currency-local flat
  synchronous protocol and has no certified-consensus activation key.
- IntegrationNet Currency snapshot protocol `1.0.0` is separately scheduled for the
  same Global snapshot ordinal, `5,923,000`. Its gate is shared by all metagraph
  lineages and is not a Currency-local v35 key. Testnet and Mainnet remain unscheduled.

## Compatibility boundaries

- `consensusSchemaVersion=35` is an immediate active-cluster compatibility fence.
- `certified-consensus-activation-ordinal` is the deterministic behavior boundary.
- `fields-added-ordinals.currency-snapshot-protocol-v1` authorizes the signed Currency
  artifact version transition from `0.0.1` to `1.0.0` using Global L0 ordinal space.
- The v35 key is in Global snapshot ordinal space. The separate Currency protocol-v1
  gate is also expressed in Global ordinal space because Global L0 accepts the binaries.
- Only the public DAG/Global incremental snapshot gains a trailing optional
  `certifiedLineage` field. Currency incremental snapshots, `GlobalSnapshot`, and
  `CurrencySnapshot` remain unchanged. Below activation the Global field is absent and
  the drop-null encoder preserves legacy Global incremental JSON bytes; at and after
  activation the Global incremental artifact hash commits to the child-carried parent
  certificate. Snapshot-info and state-proof schemas/calculation do not change.
- IntegrationNet, Testnet, and Mainnet have permanently crossed the Kryo-to-JSON
  serialization boundary. V35 is activated only on retained JSON history and does not
  support rollback/replay of its new fields through Kryo. Do not add a Kryo registration,
  fallback reader, frozen projection, or Kryo-boundary test for this new functionality.
- Every layer is fenced by the hash of its advertised Tessellation version (or
  `CL_VERSION_HASH`) and, for metagraph applications, its advertised metagraph
  version. The advertised jar hash is metadata and is not compared. L0 additionally
  requires its deterministic consensus-config hash; L1/data-L1 have no equivalent
  config-hash fence, so their coordinated version identity is the compatibility gate.
- The existing signed Currency `version` value and artifact bytes intentionally change at
  the separate global protocol-v1 boundary. Currency binary construction continues to use
  the existing JSON/Brotli state-channel serializer and the configured 512,000-byte binary
  limit; v35 never reconstructs historical Currency binaries.

Do not confuse the two gates. Nodes started with different schema/config hashes cannot
form a healthy active consensus cluster even below the activation key. Conversely,
deploying the aligned v35 jar with no public activation entry leaves Global v35 behavior
dormant. The Currency protocol gate is copied into each L0's effective consensus config
and is therefore fenced independently as well.

## Before selecting an activation key

1. Build one commit and record its tag, commit, assembly checksums, resolved
   `consensusSchemaVersion`, deterministic config hash, and environment-specific
   activation key.
2. Confirm that the target network has enough lead time to announce the activation.
3. For Global L0, confirm that the last legacy artifact contains signed controller
   evidence from which its activation committee can be seeded. Activation fails closed
   when this evidence is absent; DAG `nextFacilitators` is not a valid substitute.
   For a non-genesis DAG activation, the canonical signed seed and the final roster
   after seedlist, collateral, selector, and projector processing must each contain at
   least two members, and all current members together must be able to satisfy the
   configured `Q(N+1)` next-seat headroom. Under supermajority a participating pair can
   grow; under unanimity no finite `N` can prove an unseated `(N+1)`th signer, so a
   non-genesis certified activation in unanimity mode fails closed. The exact activation
   never falls back to local `self`. An activation configured at or before the first
   facilitated key is genesis mode: there is no legacy-to-certified exact-key transition,
   and intentional single-node development genesis remains supported. Global L0
   facilitates from its first incremental snapshot at ordinal 1 (the full genesis
   snapshot occupies ordinal 0), so the shipped dev activation `0` authenticates the
   exact canonical ordinal-1 outcome and persists it as the predecessor of the first
   certified round. The proof set is bound to the locally state-proof-validated root.
   A follower that first appears later must obtain that locally validated public root plus
   the complete retained child-carried Global incremental lineage through the tip. Interior
   SnapshotInfo preimages are not required: each child's QC authenticates its parent's
   context hash and next authority. A future independently announced, content-addressed
   checkpoint may reduce long-range I/O, but is not required for activation. One peer's
   terminal private outcome is never long-range membership authority.
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
   Exercise two honest leaders that cross the Facility phase on different valid subsets.
   Each Facility must carry a transferably signed `TriggerStatement`; the proposal must
   contain a leader-selected, independently verifiable quorum whose deterministic
   majority equals the certified trigger. One malformed statement must be ignored when
   a valid leader-bearing quorum remains, while an actually under-quorum valid set must
   hold the phase fail-closed. Local rc.10 event-pacing state must never participate.
8. Exercise both Tier-1 and Core atomic replacement: three consecutive elapsed proof
   misses, Core-quorum Silent and ReadyAtTip certificates, one ProposalQC carrying equal
   admitted/evicted sets, unchanged committee cardinality, and no standalone ECS acceptance.
9. Load test broad Core+Tier-1 signing and record round-duration p50/p95, ProposalQC
   and CoreCommitQC formation, artifact proof margin, view changes, reward breadth, and
   `dag_consensus_outcome_hook_duration_seconds` p95.
10. Inventory every active metagraph. Require upgraded Currency L0 jars/configs and
    rebuilt SDK-based Currency L1/data L1 applications before the announced global
    protocol-v1 ordinal. Confirm each signed GSI
    `unappliedGlobalChangeOrdinals` set is empty, or explicitly monitor the lineage's
    deterministic `blocked_unproven` delay until all entries at or below its selected
    Global L0 view are acknowledged. Keep dormant legacy metagraphs offline until upgraded.
    Complete and retain the release-wide active/dormant/retired/unknown lineage census from
    the [metagraph upgrade guide](../release/metagraph-upgrade-guide.md). This is an
    independent go/no-go gate: a green Global L0 build does not prove that an external
    metagraph stack was rebuilt or that its signed unapplied history is ready.
11. Replay pre-boundary Currency `0.0.1` fixtures and cross the boundary in the generated
    dev/CI metagraph. Assert the first eligible child is `1.0.0`, descendants cannot
    downgrade, and Global L0 accepts the state-channel binary.
12. Build, test, version, and deploy Snapshot Streaming against the exact Tessellation
    SDK selected for activation. The Tessellation PR workflow compiling
    Snapshot Streaming `release/testnet` against the candidate SDK is a compatibility
    test only; it is not the separately versioned Snapshot Streaming release artifact.
    That CI build must resolve both `lastLegacyStateProofOrdinal` and
    `fieldsAddedOrdinals.subTrieRoots` into `GlobalStateProofSelector`; development enables
    sub-trie roots at ordinal 0 so the E2E validates the same signed proof shape rather than
    compiling against it while leaving it dormant.
    For IntegrationNet, prepare the corresponding change in the separate
    `Constellation-Labs/snapshot-streaming` repository on its
    `release/integrationnet` branch: pin `project/Dependencies.scala` to the exact
    published Tessellation release SDK, build and run on the repository-supported JDK
    21 toolchain, and run that repository's tests and assembly. Its assembly currently
    has multiple entry points and no `Main-Class`; before using its standalone deploy
    workflow, either set `assembly / mainClass` to
    `org.constellation.snapshotstreaming.App` or invoke that class explicitly with
    `java -cp`. Add a post-restart process/current-ordinal health check: copying a jar
    and restarting systemd is not proof that indexing resumed.
    Do not inherit Snapshot Streaming's checked-in `reference.conf` environment default:
    the current external IntegrationNet branch defaults to `testnet`, and its Terraform
    application config neither overrides it nor includes the SDK's classpath
    `application.conf`. Make the generated config begin with
    `include classpath("application")`, set `snapshotStreaming.environment = integrationnet`
    explicitly, and log/assert the resolved selector at startup. Before launch, record
    `environment=integrationnet`, `lastLegacyStateProofOrdinal=5075000`, and
    `subTrieRootsActivationOrdinal=5880000`; a two-argument selector with the wrong
    environment remains incompatible.
    The Tessellation `release/integrationnet` workflow does not publish or deploy
    Snapshot Streaming. Record the SS source commit, workflow run, jar checksum/image
    digest, and Tessellation SDK version; verify that artifact re-derives and accepts
    candidate Global state proofs before selecting the activation key. Do not cross the
    activation key while the prior Snapshot Streaming build is deployed.
    IntegrationNet has already crossed the independent sub-trie proof gate at ordinal
    `5880000`; therefore its order is stricter: prove the currently deployed out-of-band
    artifact's source/checksum and full-proof compatibility, or update and deploy the
    reproducible `release/integrationnet` SS branch, before restarting/resuming Tessellation
    at any current checkpoint. Do not move the old proof gate forward: replay at and above
    `5880000` must continue using the proof shape already signed there. The external SS
    restart path resets `nextOrdinal.json` to ordinal zero, clears the configured OpenSearch
    indices with `clean_indices`, and restarts ingestion; replay then rebuilds its
    S3/PostgreSQL export state. Back up and explicitly approve/coordinate all of those
    mutations rather than treating the release-branch push as a harmless rolling restart.
    Snapshot Streaming does not otherwise write OpenSearch during ordinary ingest. Select and
    approve one of the three modes in the
    [Snapshot Streaming and Block Explorer reconciliation runbook](snapshot-streaming-block-explorer-reconciliation.md):
    ordinary no-reorg upgrade, deliberate full replay/rebuild, or canonical rollback
    divergent-suffix repair. Do not let a deployment script's default full rebuild stand in
    for an explicit mode/owner decision.
13. Verify public certified-lineage retention. From A-1 (or the canonical first
    incremental root for certification-from-genesis) through the current tip, every signed
    incremental artifact must be readable after a process restart. The independently
    trusted root and downloaded terminal must also have their complete SnapshotInfo/context
    so their state proofs can be validated. Interior SnapshotInfo follows the ordinary
    logarithmic cutoff: replay hashes no missing preimage, because the QC already certifies
    its context hash and the historical fold consumes only the authority transition.
    Exercise a fresh root-to-tip replay after deliberately pruning all non-checkpoint
    interior SnapshotInfo files.
    Live snapshot index directories must not have same-filesystem hardlink
    backups: recovery distinguishes canonical hash+ordinal pairs from torn
    hash-only content using the inode link count. Use byte-copy/object-storage
    archives or another filesystem, and time one complete hash-index recovery
    scan against an IntegrationNet source with production-scale retention before
    tagging the activating release.
14. Verify authenticated policy effects. Live nodes are still fenced to one advertised
    version and deterministic config while they execute current committee policy. The QC
    for round N must authenticate both N's exact full/Core authority and the exact
    `nextRoundAuthority` for N+1, plus the post-round operational-state commitment.
    Historical replay must use the fixed BFT floor and consume those certified effects;
    it must not execute the joining binary's current quorum fraction, Core sizing,
    selector, seedlist, allowance, or collateral policy against old rounds. Exercise a
    fresh root-to-tip downloader after changing current quorum/Core policy and removing an
    old member from the current seedlist/allowance inputs; canonical history must still
    verify. Separately prove that the newly joined live cluster uses its current fenced
    policy only when proposing a future `nextRoundAuthority`.

    In certified derivation, peer-local `removedFacilitators` observations are not an
    input to the committed operational state. The derivation reads the QC-bound
    `ProposalValue.evictedPeers`, and Finished writes that exact set back into the
    outcome. A late Facility or view-local removal observation therefore cannot change
    `nextOperationalStateHash`; terminal replay verifies both the evicted set and the
    complete operational-state commitment.

    No SemVer, assembly hash, or self-declared `policyId` is historical authority. If the
    meaning or safety rules of `ProposalValue`, `CertifiedRoundAuthorityV1`, or its QCs
    change, introduce a new schema variant and coordinated activation rather than
    reinterpreting v35 bytes.
15. Exercise env-only recovery as a process-level public-durability test, not only a
    pure reconstruction test. Start exactly one controlled `run-rollback` source and
    the selected source validators with the same env committee while unrelated
    community validators have no recovery env. Prove `R+1` commits and disarms the
    sources but is not yet publicly durable; then prove `R+2` carries the complete
    `R+1` QC. Stop every source, remove every private outcome/sidecar, and require a
    fresh env-free validator to reconstruct and adopt from public `R/R+1/R+2` data.
    The negative `R+1`-only/all-source-loss case must fail closed. Repeat across the
    exact activation boundary. In particular, prevent the first certified round `A`
    from finalizing, assert that recovery seeds at `A-1` and `A-2` are rejected, then
    perform the reconciled `<= A-3` recovery, rebuild the legacy evidence window,
    cross `A`, and reach `R+2` public durability. Complete a later full cold restart
    with the env absent before declaring recovery activation-ready.

Capacity-plan the added certificate history before activation. At the measured 73-seat
committee, `CertifiedOutcome` is approximately 30 KiB per round, or roughly 21 GiB/year at a
sustained 43-second cadence (20.89 GiB/year before filesystem overhead). Signed incremental artifacts were
already retained; interior SnapshotInfo remains logarithmically retained, while the exact A-1
activation-parent SnapshotInfo is explicitly pinned for the lifetime of that configured activation,
so v35 does not add the rejected
approximately 1.2-TB/year contiguous-context history or an O(epoch)-per-round cutoff scan. Record
the actual certificate/artifact disk-growth rate and free-space runway during the IntegrationNet
soak rather than treating the wire-size estimate as a filesystem-capacity measurement.

The reproducible `CertifiedConsensusSuite` production-JSON measurement at 73 seats is:

```text
TriggerStatement quorum evidence  34,938 bytes  (consensus message only)
ProposalValue                     24,644 bytes
ProposalQC                        27,558 bytes
CoreCommitQC                       2,993 bytes
CertifiedOutcome                  30,582 bytes  (persisted/child-carried)
```

The same suite measures sizes at 3, 31, 73, 100, 200, and the configured maximum 1,000
facilitators. These are wire/preimage measurements rather than filesystem-capacity estimates;
soak measurements remain mandatory.

The Global download validator performs two O(epoch) sequential artifact passes for a fresh
activation-root-to-tip download with no later recovery boundary: one backward scan locates the
latest public recovery reset, then one forward fold authenticates the selected segment. Its
`tailRecM` cursors retain only the authenticated predecessor and at most two public frames, so the
heap/stack bound is O(1), independent of epoch length. A missing artifact in either pass fails closed;
an unauthenticated local reset-index hint or a capped scan must not silently skip a real recovery
boundary. Exercise both passes over a multi-thousand-frame lineage and monitor wall-clock bootstrap
time. A future independently authorized checkpoint may bound that I/O, but is not an activation
blocker.

## Deployment sequence

1. Choose and announce one future Global v35 activation key. If Currency protocol v1 is
   included, separately choose and announce one future Global L0 ordinal for all metagraph
   lineages; it need not equal the v35 key. For IntegrationNet `v4.1.0-rc.13`, both
   separately configured gates are `5,923,000`.
2. Stop the complete active cluster. Archive snapshots, certified-outcome sidecars,
   certified-vote-lock journals, configuration, and logs; verify a coherent
   pre-activation checkpoint. Version 35 has not been publicly activated on any operated
   network. Any private/dev environment that ran an earlier experimental v35 shape must
   discard that experimental certified history and restart from a verified pre-v35 anchor;
   the final required `ProposalValue` fields intentionally do not decode the abandoned shape.
3. Install the same v35 assembly and the same resolved activation configuration on
   every active facilitator. Rebuild every active metagraph stack before the global
   Currency-protocol boundary. Deploy the separately built and verified matching
   Snapshot Streaming artifact from its own environment release branch before crossing
   either activated state-application boundary. A green Tessellation PR
   `snapshot-streaming` E2E is necessary compatibility evidence, not proof that this
   production artifact was released. Snapshot Streaming/Block Explorer release approval
   and complete metagraph-stack release approval are separate recorded go/no-go gates.
   Do not canary a mixed active consensus fleet.
4. Cold-start the cluster and verify identical deterministic config hashes and normal
   legacy progress below the key.
5. Before crossing, verify every expected active node is on the recorded jar/config.
6. At the key, verify the canonical legacy-window reset, frozen full/Core hashes,
   ProposalQC, CoreCommitQC, full artifact finality floor, persisted certified sidecar,
   child-carried parent certificate, and identical semantic value/derived operational
   outcome on multiple nodes. Raw sidecar files and equivalent carried QCs may contain
   different valid signature subsets and need not be byte-identical.
7. Continue watching admission/eviction cadence, signer/reward population, finality
   headroom, view changes, round duration, direct-send queue pressure, soft resets, and
   certified recovery. Include
   `dag_consensus_certified_recovery_total`,
   `dag_consensus_certified_recovery_candidate_total`, and
   `dag_consensus_certified_recovery_boundary_total`,
   `dag_consensus_outcome_sidecar_total`/`dag_consensus_outcome_hook_duration_seconds`, plus
   `dag_consensus_trigger_evidence_excluded_total`, and
   `dag_consensus_admission_pre_proposal_grace_ms` in the activation dashboard. At the shipped
   one-second probe bounds, an open cadence round with a nominee may report 3500 ms while its
   current-key Facility/probe/vote pipeline completes; a valid certificate closes the wait early.
8. At the Currency protocol boundary, verify each active lineage's first eligible
   successor carries `version=1.0.0`. Monitor
   `dag_currency_l0_snapshot_protocol_total{outcome}` and do not restart a
   `blocked_unproven` lineage; inspect its signed unapplied history.

Before activation, verify the ordinary-download lineage boundary on every source node:

- the exact activation outcome validates from the locally stored, state-proof-checked
  A-1 snapshot. State-proof/file validation uses the ordinal-selected historical
  rules, while the reconstructed legacy outcome identity and newly reset v35 committee
  hash use the current consensus hasher, matching live activation even across a
  hash-transition boundary. Before signed controller evidence can seed authority, the
  A-1 artifact's embedded ordinal must equal the requested index, its signature is verified
  with that expected ordinal's hasher, proof signer IDs must be unique, and the
  context-derived state proof must equal the artifact state proof. Live activation has already
  applied the then-current join-fenced seedlist/collateral policy. A later historical replay
  deliberately does not apply today's mutable seedlist to that old canonical root. The exact
  A-1 SnapshotInfo is permanently protected from ordinary logarithmic pruning. Every public
  network's activation root is already in the JSON-serde era; no v35 field has a Kryo
  fallback or compatibility path;
- predecessor validation is read-only: it reconstructs and checks the persisted state
  proof without synchronizing/rewinding the MPT and without deleting snapshot files;
- the canonical first incremental genesis root at key 1 and an exact
  `CL_GL0_RECOVERY_SEED_COMMITTEE` anchor are the only locally persisted
  uncertified roots. Genesis authority is bound exactly to the locally validated
  artifact's proof signers. A recovery root is bound on selected nodes to the
  validated public anchor, exact env committee, seedlist/allowance/collateral checks,
  and the all-member barrier. Live nodes project its first certified child's next
  authority through the same typed committee projector; historical adoption verifies
  the fixed-floor QC and its certified authority effect without re-running current policy;
- each public child at N+1 carries N's complete `CertifiedOutcome`. A downloader starts
  from its independently validated A-1/genesis root and walks every public signed
  incremental artifact in order. The prior QC fixes the authority for N; N's QC fixes the
  authority for N+1 and commits the terminal operational-state preimage. Historical
  verification uses the fixed BFT floor rather than current policy. Artifact signatures,
  unique signers, parent hashes, committee continuity, QC bindings, and every
  layer-specific link are checked before anything is installed. Complete context/state-
  proof validation is performed at the root and terminal; an interior context hash is
  already certified and its pruned SnapshotInfo preimage is not loaded;
- the complete replay is atomic and constant-memory: a missing/corrupt interior artifact fails before
  application storage, consensus storage, safety locks, or sidecars change. No verified
  prefix becomes authority on its own;
- outcome sidecars are not download authority or a committee-projection fast path. A
  certificate authenticates its ProposalValue, not every derived operational field in
  the JSON outcome; therefore a parseable sidecar cannot safely replace public replay.
  Checkpoint-backed provenance may permit an authenticated cache in a future version,
  but this implementation always replays from an independently validated public root.
  After an explicit env recovery, the first successor's ordinary QC is reset authority
  under the permissioned seedlist/collateral policy, but it is terminal/private on the
  recovery sources at `R+1`. The following child `R+2` must carry that complete QC before
  an env-free validator can reconstruct the canonical root from public data and replay
  the latest reset-to-tip epoch. This is an explicit permissioned trust boundary: the
  independent authority is the controlled rollback lead, env-selected source cohort,
  full-fleet cold restart, canonical source-chain selection, and Snapshot Streaming
  reconciliation. Historical replay validates the resulting fixed-floor QC but never
  re-applies today's mutable seedlist to that old reset;
- full snapshot types remain frozen. A future long-range checkpoint must be a separate,
  versioned, content-addressed manifest paired with an ordinary combined incremental
  checkpoint, never a field on `GlobalSnapshot` or `CurrencySnapshot`. It must bind the
  layer/network, ordinal, artifact and context hashes, certified full/Core authority,
  certified tip, and minimal operational continuation state. Authority must come from an independently
  announced manifest hash; neither an embedded committee nor the existing best-effort
  `.meta` sidecar can self-authorize it. The v35 path deliberately relies on retained
  root-to-tip incremental artifacts, not contiguous SnapshotInfo. Checkpoint-manifest
  schema, crash-safe storage, authority distribution, and atomic adoption remain a future
  optimization rather than a v35 activation dependency.

The env recovery equality check applies only while selected nodes install its exact
anchor. After that anchor is locally accepted, successor outcomes use the ordinary
certified-lineage validator. Comment/remove the env after the first successor without
restarting the running cohort, but keep community nodes and restart automation held:
the first successor's QC becomes verifiable to env-free community validators only when
the second successor carries it publicly. Require
`dag_consensus_recovery_seed_boundary_publicly_durable == 1` plus the documented
headroom gate before release. Leaving the env armed makes every later external source
restart a new recovery attempt and is prohibited during normal operation.

Currency L0 does not switch into certified consensus at this key. It uses its local
synchronous all-member phases and fixed-universe ACK removal on both sides of the Global
activation. Currency protocol-v1 changes signed history semantics only at its separate
Global-ordinal gate. Its Facility availability barrier actively replicates an origin's
exact signed event envelope to every round-start facilitator before advertising the hash.
Monitor `dag_currency_consensus_event_replication_total{outcome}` together with
`dag_currency_consensus_facility_event_deferred`: sustained `request_failed`/`rejected`
growth or a non-zero deferred gauge means event transport is preventing inclusion, not
that synchronous consensus should be weakened.

The `certifiedVoteLocks` directory is pre-finalization safety state. Do not remove it
to clear a stalled round, and do not treat a decode error as a missing cache. Ordinary
same-key retries retain the record. A certified finalization removes it only after the
matching complete-outcome sidecar write succeeds; rollback/download initialization
have deliberately different semantics. Download/restart removes finalized or stale
records at or below its accepted ordinal and retains a possible next-key lock. Only an
accepted rollback policy prunes records above its accepted ordinal; an aborted solo
`run-rollback` can therefore discard locks before it parks. This is one reason the
operated procedure always stops the fleet, starts exactly one controlled rollback lead,
and starts every other node as a validator.

No restart is required merely because the ordinal crosses; the aligned nodes switch
deterministically at the configured key.

## Availability and rollback

V35 does not shrink the current-round safety universe. Loss of the configured Core
quorum prevents the prepare/commit certificate; loss of the configured full-committee
quorum prevents artifact finality. Either condition may require a coordinated restart.

The immediately active Global L0 bridge is deliberately halt-safe as well. Under
`FreezeAfterVote`, a node that has signed at a key will not abandon into a conflicting
same-key attempt and will not emit a VCC/TC that would authorize unsafe re-voting. Under
`RetainSigningLeases`, a timeout certificate also does not delete its silent non-voters
from the current-round denominator. Thus a cluster-wide 2-of-4 (or analogous subquorum)
stall with no corroborated peer ahead has no automatic protocol escape: it remains visibly
held until the missing members recover or the operator performs the documented coordinated
restart/recovery. This is the accepted availability-for-safety trade, not a monitor signal
to restart one node independently.

Global L0 replacement is preventive, not subquorum recovery. It runs only while the
original frozen Core can still certify the complete N-to-N transition. Operators must
alert on any proposal where admitted and evicted counts differ; honest nodes reject it.

If activation fails:

1. Stop the full cluster; do not let two partial histories race.
2. Archive logs and sidecars before changing anything.
3. Stop Snapshot Streaming before selecting or installing a rollback lineage. Record its
   database tip and the ordinal/hash already exported to S3/PostgreSQL, plus the state of any
   downstream Block Explorer/indexer. If any exported ordinal will be replaced, execute the
   canonical rollback divergent-suffix mode in the
   [SS/BE reconciliation runbook](snapshot-streaming-block-explorer-reconciliation.md).
   Reconcile those rows/objects, its seed marker, and downstream indexes to the chosen canonical
   lineage before resuming ingest; source-majority validation does not make ordinal-unique
   storage reorg-aware.
4. Restore the verified pre-activation checkpoint and the prior coherent jar/config.
5. Move the activation key only through another announced, full-cluster rollout.

Do not work around a fast-path predecessor-sidecar error by copying a peer's JSON
sidecar into the local directory. The validator must fall back to the retained public
root-to-tip lineage and derive authority sequentially. Copying unverified private
outcome bytes would reintroduce the circular committee proof this boundary is designed
to reject.

Currency recovery remains the stable permissioned topology: one controlled
`run-rollback`/`run-genesis` lead starts a self-only synchronous committee and every other
Currency node runs `run-validator`, downloads public history, observes successors, and
registers. There is no signed Currency recovery plan and no post-v35 certified Currency
root. The `--allow-solo-consensus` flag separately arms dormant-lineage publication refresh;
it is one-shot operational authority and must not be persisted in unattended restart
automation.
