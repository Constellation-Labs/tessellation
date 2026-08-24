# V35 certified-consensus rollout

This runbook accompanies [ADR-0032](../adr/0032-certified-consensus-outcomes.md) and
[ADR-0033](../adr/0033-versioned-currency-snapshot-history.md).

## Scheduled activations

- No public activation is currently scheduled. The former IntegrationNet DAG L0 key at
  `5,890,500` was withdrawn before activation after the rc.5/rc.6 incidents. A replacement
  key must be selected and announced only after the rc.7-compatible dormant path has
  soaked successfully.
- Every Currency L0 remains disabled here. Each metagraph has an independent ordinal
  space and must select and coordinate its own activation key. Mainnet, IntegrationNet,
  and testnet remain disabled for both layers.
- Currency snapshot protocol `1.0.0` is also unscheduled on every public network. Its
  gate is a GLOBAL L0 ordinal shared by all metagraph lineages, not a Currency-local v35
  key. It may be announced in the same release window, but it remains a distinct gate.

## Compatibility boundaries

- `consensusSchemaVersion=35` is an immediate active-cluster compatibility fence.
- `certified-consensus-activation-ordinal` is the deterministic behavior boundary.
- `fields-added-ordinals.currency-snapshot-protocol-v1` authorizes the signed Currency
  artifact version transition from `0.0.1` to `1.0.0` using Global L0 ordinal space.
- DAG L0 and every Currency L0 use their own snapshot ordinal space.
- The public DAG and Currency incremental snapshots gain a trailing optional
  `certifiedLineage` field, and their full snapshots gain a trailing optional
  `certifiedCheckpoint` field. Below activation those fields remain absent and the
  drop-null encoder preserves legacy artifact bytes. At and after activation the
  incremental artifact hash intentionally commits to the child-carried parent
  certificate. Snapshot-info and state-proof schemas/calculation do not change.
- The existing signed Currency `version` value and artifact bytes also intentionally
  change at the separate global protocol-v1 boundary.

### Historical Currency binary encoder contract

Certified Currency lineage V1 carries only the parent binary's last-hash, fee, and complete
proof envelope. A verifier reconstructs the omitted content by serializing the public signed
Currency artifact with the same `JsonSerializer` pipeline used by
`StateChannelSnapshotService.createBinary`: sorted/drop-null JSON, UTF-8, then brotli4j 1.12.0
at quality 2. It then rebuilds and hashes `StateChannelSnapshotBinary` and verifies the carried
proofs.

That reconstruction makes the exact V1 output historical protocol code. The brotli4j version
is pinned in `project/Dependencies.scala`, and `CertifiedConsensusSuite` pins a complete
reconstruction preimage. A dependency, printer, outer `Signed` encoder, Currency artifact
encoder, compression parameter, or native output change that alters those bytes must fail the
golden test. Do not update the expected hash to make such a change pass.

Future encoding evolution must introduce a new `CertifiedLayerEvidence` variant/version and
retain the V1 verifier for historical lineage. The versioned sealed-trait alternative is the
wire discriminator; application semver is not used to reinterpret historical bytes.

Do not confuse the two gates. Nodes started with different schema/config hashes cannot
form a healthy active consensus cluster even below the activation key. Conversely,
deploying the aligned v35 jar with no public activation entry leaves the v35 behavior
dormant. The Currency protocol gate is copied into each L0's effective consensus config
and is therefore fenced independently as well.

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
   never falls back to local `self`. An activation configured at or before the first
   facilitated key is genesis mode: there is no legacy-to-certified exact-key transition,
   and intentional single-node development genesis remains supported. Both L0 layers
   facilitate from their first incremental snapshot at ordinal 1 (the full genesis
   snapshot occupies ordinal 0), so the shipped dev activation `0` authenticates the
   exact canonical ordinal-1 outcome and persists it as the predecessor of the first
   certified round. The proof set is bound to the locally state-proof-validated root.
   Currency additionally binds the embedded artifact proof envelope to its binary bytes,
   but the current implementation is **not activation-ready**: collecting every signer
   identity does not make randomized ECDSA proof bytes unique. A signer that restarts or
   otherwise signs the same artifact twice can produce two valid proof bytes, and honest
   recipients can retain different variants. The binary-preimage agreement design must be
   closed before the schema is frozen or an activation ordinal is announced. The preferred
   narrow candidate from the internal audit is an epoch-scoped deterministic ECDSA policy
   for these artifact signatures only; do not change the global signer or activate it until
   fixed-vector, repeat/concurrency/restart, existing-verifier, and binary-equality tests pin
   the exact provider/algorithm behavior.
   Currency genesis defers its first child by one ordinary round interval, matching DAG
   L0, and a downloader that sees that root bypasses the legacy four-snapshot observation
   offset so it initializes consensus at ordinal 1. A data-application follower restores
   its locally compiled genesis calculated state at that boundary and verifies its
   application-defined hash against the signed root proof. The genesis producer must
   commit that real hash, not the legacy `Hash.empty` placeholder, and the follower must
   not substitute a peer's potentially newer calculated state. Together these let
   joining validators persist the authority root before ordinal 2 appears. A follower
   that first appears later must obtain the locally validated public root plus the complete
   retained child-carried lineage through the tip. Once checkpoint adoption is implemented,
   an independently announced full-snapshot hash may replace that history requirement. One
   peer's terminal private outcome is never long-range membership authority.
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
    restart path resets its configured next ordinal and clears its OpenSearch index; back
    up the stores and explicitly approve/coordinate that rebuild rather than treating the
    release-branch push as a harmless rolling restart.
13. Verify public certified-lineage retention. From A-1 (or the canonical first
    incremental root for certification-from-genesis) through the current tip, every
    incremental artifact and snapshot-info/context required for sequential validation
    must be readable after a process restart. The v35 storage policy retains this
    context range contiguously in addition to legacy logarithmic checkpoints. Do not
    prune an interior frame until an independently announced full checkpoint is both
    produced and supported by the download path.
14. Close historical consensus-policy replay. The current implementation replays old
    rounds with the joining binary's current `ConsensusConfig`, seedlist, allowance
    list, and eligibility rules. The live join fences prevent a mixed current session,
    but do not make those current inputs valid historical policy. Before activation,
    either implement authenticated historical-policy selection, define and implement a
    trusted checkpoint/policy-epoch boundary, or prove and formally adopt that every
    committee-affecting policy input is immutable for the complete certified epoch.
    Exercise at least one config-hash, seedlist, and allowance-list transition with a
    fresh root-to-tip downloader. A current-policy rejection of previously certified
    history is a failed gate, not permission to skip validation.

Capacity-plan this retained epoch before activation. At the measured 73-seat committee,
`CertifiedOutcome` is approximately 29 KiB per round, or roughly 21 GiB/year at a sustained
43-second cadence, before filesystem/JSON overhead and the additional contiguous snapshot-info
history. Record actual disk-growth rate and free-space runway during the IntegrationNet soak;
do not extrapolate only from legacy logarithmic snapshot-info retention. The current flat-directory
cutoff path enumerates stored snapshot-info ordinals while the certified range is retained, making
maintenance O(epoch) per round and O(epoch^2) cumulatively. Activation also requires an incremental
deletion/index strategy (or partitioned storage that skips the retained epoch) and a multi-year
cardinality load test; disk capacity alone is not sufficient.

The current DAG and Currency download validators also materialize the complete trusted-root-to-tip
`PublicRound` sequence, while the shared verifier returns every derived state. Because sidecars are
deliberately excluded from authority, this O(epoch) CPU and O(epoch) live-heap cost applies to every
post-v35 fresh download and ordinary cold restart, not merely a cache-miss fallback. Activation
requires a constant-memory sequential fold with bounded boundary discovery, or adopted authenticated
checkpoints that impose and test a hard maximum segment length.

## Deployment sequence

1. Choose a future activation key independently for each L0 cluster. If Currency
   protocol v1 is included, separately choose and announce one future Global L0 ordinal
   for all metagraph lineages.
2. Stop the complete active cluster. Archive snapshots, certified-outcome sidecars,
   certified-vote-lock journals, configuration, and logs; verify a coherent
   pre-activation checkpoint.
3. Install the same v35 assembly and the same resolved activation configuration on
   every active facilitator. Rebuild every active metagraph stack before the global
   Currency-protocol boundary. Deploy the separately built and verified matching
   Snapshot Streaming artifact from its own environment release branch before crossing
   either activated state-application boundary. A green Tessellation PR
   `snapshot-streaming` E2E is necessary compatibility evidence, not proof that this
   production artifact was released. Do not canary a mixed active consensus fleet.
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
   `dag_consensus_trigger_evidence_excluded_total` and
   `dag_currency_consensus_trigger_evidence_excluded_total`, and
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
  with that expected ordinal's hasher, proof signer IDs must be unique and seedlisted, and
  the context-derived state proof must equal the artifact state proof. The shared Global L0 artifact-hash helper preserves the V1
  projection required by historical Kryo hashes;
- predecessor validation is read-only: it reconstructs and checks the persisted state
  proof without synchronizing/rewinding the MPT and without deleting snapshot files;
- the canonical first incremental genesis root at key 1 and an exact
  `CL_GL0_RECOVERY_SEED_COMMITTEE` anchor are the only locally persisted
  uncertified roots. Genesis authority is bound exactly to the locally validated
  artifact's proof signers. A recovery root is bound on selected nodes to the
  validated public anchor, exact env committee, seedlist/allowance/collateral checks,
  and the all-member barrier. Its first certified child is projected through the
  same typed committee projector and verified through the ordinary bound-QC adoption
  path;
- each public child at N+1 carries N's complete `CertifiedOutcome`. A downloader starts
  from its independently validated A-1/genesis root, walks every public artifact and
  context in order, re-derives membership and layer state, and obtains only the terminal
  certificate from the authenticated outcome endpoint. Signatures, unique signers,
  parent hashes, state proofs, seedlist eligibility, committee bindings, and every
  layer-specific link are checked before anything is installed;
- the complete replay is atomic: a missing/corrupt interior frame fails before
  application storage, consensus storage, safety locks, or sidecars change. No verified
  prefix becomes authority on its own;
- outcome sidecars are not download authority or a committee-projection fast path. A
  certificate authenticates its ProposalValue, not every derived operational field in
  the JSON outcome; therefore a parseable sidecar cannot safely replace public replay.
  Checkpoint-backed provenance may permit an authenticated cache in a future version,
  but this implementation always replays from an independently validated public root.
  After an explicit env recovery,
  the first successor's ordinary QC is public reset authority under the permissioned
  seedlist/collateral policy: an unconfigured validator reconstructs the canonical root
  from that QC and the independently validated public parent, then replays only the
  latest contiguous reset-to-tip epoch;
- Currency children carry only the parent binary's last-hash, fee, and proof envelope.
  The verifier reconstructs the exact binary content from the already validated public
  parent artifact with the pinned V1 JSON+Brotli encoder, hashes it, then verifies its
  frozen-committee proofs. Carrying the full parent binary is forbidden because it
  would recursively embed the entire lineage. This bounded representation does not by
  itself solve live binary-preimage agreement: the exact embedded artifact proof bytes
  must first be consensus-selected or removed from the preimage under a reviewed,
  versioned Currency representation. Sorting proofs or requiring the complete signer-ID
  set is insufficient because ECDSA signatures are randomized; and
- full snapshots reserve an optional `CertifiedCheckpointV1` projection. Its containing
  full-snapshot hash must be independently announced; the certificate inside cannot
  authorize the committee that verifies itself. The current initial-activation path
  deliberately relies on contiguous root-to-tip retention. Do not treat the checkpoint
  field as an operational recovery mechanism until checkpoint publication, authority
  distribution, and download adoption are separately implemented and exercised.

The env recovery equality check applies only while selected nodes install its exact
anchor. After that anchor is locally accepted, successor outcomes use the ordinary
certified-lineage validator, and the first successor QC makes the boundary verifiable
to unconfigured community validators. Comment/remove the env after the first successor
without restarting the running cohort. Leaving it armed makes every later external
source restart a new recovery attempt and is prohibited during normal operation.

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

V35 does not shrink the current-round safety universe. Loss of the configured Core
quorum prevents the prepare/commit certificate; loss of the configured full-committee
quorum prevents artifact finality. Either condition may require a coordinated restart.

Global L0 replacement is preventive, not subquorum recovery. It runs only while the
original frozen Core can still certify the complete N-to-N transition. Operators must
alert on any proposal where admitted and evicted counts differ; honest nodes reject it.

If activation fails:

1. Stop the full cluster; do not let two partial histories race.
2. Archive logs and sidecars before changing anything.
3. Restore the verified pre-activation checkpoint and the prior coherent jar/config.
4. Move the activation key only through another announced, full-cluster rollout.

Do not work around a fast-path predecessor-sidecar error by copying a peer's JSON
sidecar into the local directory. The validator must fall back to the retained public
root-to-tip lineage and derive authority sequentially. Copying unverified private
outcome bytes would reintroduce the circular committee proof this boundary is designed
to reject.

The legacy Currency L0 `--allow-solo-consensus` flag remains one-shot and must never be
persisted in systemd or automatic restart configuration. It is **not yet a valid
post-v35 recovery mechanism**. Currency `run-rollback` currently reconstructs only the
public signed artifact, snapshot info, and last binary hash; it does not reconstruct the
certified outcome/binary lineage required by `CurrencyCertifiedDownloadValidator`.
Moreover, the terminal Currency artifact carries its parent's QC while its own QC is
normally child-carried, so a fully stopped lineage may have no public child from which to
recover terminal authority. Before activation, implement and test an explicit certified
Currency rollback/root path—including the terminal-with-no-child case—or remove the
post-v35 solo-recovery promise. A legacy flag cannot bypass certified initialization.
