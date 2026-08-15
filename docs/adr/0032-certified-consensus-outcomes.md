# ADR-0032: Certified consensus outcomes over a frozen round context

Date: 2026-08-11

Status: Proposed

## Context

Global L0 halted for seven hours after different nodes finalized the same snapshot
artifact at ordinal 5,881,764 but retained different outcome metadata. One cohort
finished in view 2 after an in-round facilitator shrink; another finished in view 0
with the original committee. Both artifact proof sets were valid because artifact
signatures certify only the artifact hash. The differing controller evidence then
produced different artifacts at the next ordinal, and same-key recovery had no
certified complete outcome to adopt.

The same structural gap exists in DAG L0 and Currency L0: code later consumes an
artifact quorum as though it also certified proposal fields that determine the
persisted outcome and the next round. The artifact signature must retain its present
meaning so existing metagraph snapshot/state-proof validation remains compatible.

## Decision

1. Add a v35 `ProposalValue` that contains every view-independent semantic input to
   the persisted outcome: layer/network/key/parent identity, artifact and context
   hashes, frozen full and Core committees, committed view, trigger, canonical
   admission and eviction targets, responder/self-health evidence, timeout voters,
   and leader-proposed consensus end time.
   `committedView` is the view in which that semantic value first obtained its prepare
   QC. A later ProposalInstance may carry it in a higher transport view, but must retain
   `committedView` byte-for-byte; this lets deterministic view-derived accounting follow
   the certified value rather than whichever view a local node happened to finalize in.
2. Represent canonical collections in the Scala types themselves (`NonEmptySet`,
   `SortedSet`, and `SortedMap`). There is no separate `canonicalize` function,
   `canonicalSignBytes`, manual byte concatenation, or new hash algorithm.
   `ProposalValue` and domain-separated certification statements use the existing
   Circe codecs, `JsonSerializer`, `Hasher[F]`, `Signed.forAsyncHasher`, and
   `Signed.hasValidSignature` infrastructure.
3. Keep legacy artifact QCs and v35 semantic QCs as distinct public types because
   they certify different facts. Share their type-independent lock, quorum,
   selection, and signature-verification algorithms through generic node-shared
   helpers. Share the complete v35 prepare/QC orchestration between DAG and Currency;
   keep genuinely layer-specific artifact/context derivation explicit.
4. Use explicit codecs only for the recursive QC-bearing message paths affected by
   the known Circe/Magnolia concurrent first-initialization defect. Those codecs use
   the repository's generic `Signed[A]` codec and preserve the ordinary case-class
   JSON field shape. Concurrent first-touch and golden-hash tests pin this boundary.
5. Freeze the round-start Core and Core+Tier-1 committee for the complete ordinal.
   Core prepares one exact `ProposalValue`, forms a BFT-supermajority ProposalQC,
   then forms a separately domain-separated Core commit QC. The frozen full committee
   continues to sign the unchanged artifact hash and satisfy the existing finality
   floor.
6. Carry the highest verified complete ProposalQC through view-change and timeout
   votes. Verify every advertised candidate before selecting the unique highest view;
   an invalid higher claim cannot eclipse a valid lower QC, and divergent valid QCs at
   the same highest view fail closed.
7. Admissions and evictions certified in ordinal N affect membership only in N+1.
   Disable current-round committee mutation and quorum-denominator shrink on the v35
   path. If fewer than the configured supermajority of the frozen Core remains live,
   the round may halt until a coordinated cluster restart. This availability trade is
   accepted in preference to changing the safety universe mid-round. The conservative
   rc.7 Global L0 policy continues to disable health-derived contraction. After v35
   activation, Global L0 may certify one bounded atomic replacement for N+1: every
   `evictedPeer` must be paired one-for-one with an `admittedPeer` in the same
   ProposalValue, so this health-derived path cannot shrink the signing roster or
   finality floor. Core certifies the complete pair; standalone or unequal eviction
   batches fail closed. Deterministic administrative/eligibility filtering (seedlist,
   next-context/on-chain collateral eligibility and configured facilitator selection)
   remains separate authority and may still remove an ineligible incumbent. The replacement
   reuses the existing ACS/ECS messages and ProposalValue sets and does not introduce a
   serialization or hash domain. Currency L0 retains its existing eviction authority and
   does not adopt the Global L0 replacement policy. These are separate policy capabilities:
   freezing N must not implicitly enable health-derived contraction.
8. At the exact activation key, discard legacy node-local controller/evidence,
   recent-signer/proof, penalty/probation, and PeerHistory windows before deriving the
   first v35 round. Both layers seed membership from the latest controller-evidence
   transition inside the signed legacy artifact's PeerHistory and fail closed if no
   signed seed exists. DAG's historical `nextFacilitators` field is a singleton
   compatibility field and is explicitly not an activation committee source. Because
   signed PeerHistory is constructed before its own outcome exists, this bridge delays
   the last legacy membership delta by one round rather than reading a fresher but
   node-local sidecar.
9. Persist the complete typed layer `ConsensusOutcome` beside the normal snapshot after
   (and only after) the last-outcome compare-and-set succeeds. Storage uses one generic
   ordinal sidecar over the existing `JsonSerializer`; malformed data is a cache miss.
   Sidecars reuse the existing logarithmic snapshot-info cutoff rather than introducing
   another retention configuration.
   Serve exact historical outcomes through the existing authenticated
   `/consensus/specific/outcome` route. Before abandonment, a bounded peer sample may be
   adopted only after the layer re-derives the outcome against its locally known parent
   and frozen sets and verifies both Core QCs and artifact proofs. Currency additionally
   retains/verifies its already-existing fully signed `StateChannelSnapshotBinary`,
   because its binary hash cannot be authenticated from a scalar alone. Two valid
   semantic value hashes fail closed. Arbitrary long-range certification is out of scope
   until a certificate chain or trusted checkpoint is specified.
   Ordinary download uses the same rule before any application-storage alignment or
   consensus initialization. At the exact activation key, the locally downloaded and
   state-proof-validated A-1 artifact/context is the independent root. After activation,
   the node must hold the immediately preceding outcome in its locally produced or
   previously validated certified sidecar and tie that sidecar back to its local public
   snapshot. This predecessor check is non-mutating: it reconstructs the state proof
   without synchronizing the MPT or deleting local files. Historical state-proof/file
   validation uses the ordinal-selected rules. The reconstructed legacy outcome identity
   and newly reset activation value use the current hasher, exactly as live state
   creation does. One typed Global L0 artifact-hash helper preserves the V1 projection
   when a caller intentionally computes a historical Kryo identity. The existing
   bound-outcome verifier then re-derives the candidate against that predecessor.

   Two explicit uncertified roots are also locally authoritative: certified-consensus
   genesis and an exact operator-signed recovery-plan anchor. Production persists only
   their canonical typed root shape. Genesis validation additionally derives the exact
   committee from the locally accepted artifact's proof signers; recovery-plan validation
   derives it from the verified signed plan. The first certified child is derived with
   the same committee projector and verified with the same bound-outcome path; structural
   shape by itself never authenticates peer-supplied bytes. Recovery-plan equality is
   checked only at the anchor, not against later certified successors. A fresh post-activation
   node without local lineage or an explicit signed recovery-plan/checkpoint fails
   closed; one authenticated peer response is not membership authority.
10. Persist each local certified vote lock before emitting its `OutcomeVote`, and
    persist every verified QC advancement before it can influence gossip or commit
    progression. DAG L0 and Currency L0 share one generic
    `CertifiedVoteLockPersistence[F, Key]`; their production wiring uses the same
    SnapshotOrdinal filesystem implementation and the repository `JsonSerializer`.
    This journal is node-local safety state, not a consensus message, public snapshot,
    hash input, or activation field.

    Journal replacement and certified-outcome sidecar replacement share one
    `CrashSafeAtomicFileWriter`: write a temporary file in the destination directory,
    force file contents and metadata, require an atomic rename, then force the directory
    where supported. The memory/dirty and clean transitions are cancellation-masked,
    while the filesystem write remains cancelable; cancellation during I/O leaves the
    stricter lock dirty. A dirty in-memory lock is never returned as usable safety
    state; reads retry its write and fail closed until it is durable. A missing record
    means the node has not durably voted at that key. A malformed/truncated record is
    not a cache miss: startup and voting fail closed. Parsed QCs are hydrated
    conservatively for lock safety and are cryptographically re-verified against the
    frozen committee before a view-change or timeout vote may carry them.

    Same-key abandonment, soft reset, and transient resource cleanup never delete the
    journal. The record is deleted only after the complete certified outcome sidecar
    has been durably written. Ordinary download/restart initialization removes records
    made stale by a finalized boundary while retaining an in-flight next-key lock.
    Explicit coordinated rollback is a distinct lifecycle hook and additionally prunes
    records above its accepted boundary. Conflating those paths would erase the exact
    lock needed after a process crash. This closes the process-restart double-vote
    window without inventing a second serialization or hash scheme.
11. Make direct consensus delivery enqueue-only from the FSM's perspective, and make
    every soft reset schedule a replacement action. Network fanout latency must not
    block the serialized consensus command loop.

`observedResponders`, leader-aggregated self-health, and the resulting evidence remain
leader-authored claims. V35 bounds them structurally, makes them explicit, and has Core
certify the exact claim; followers do not independently reconstruct the leader's local
observation. This preserves the v34 permissioned-operator trust model. A misleading
leader can be identified and governed out of band, but cannot make honest finalizers
consume two different claims as one certified value.

## Activation and compatibility

`consensusSchemaVersion = 35` and the resolved activation key both participate in
`deterministicConfigHash`. The version is an immediate wire/config compatibility
fence when a process starts; it is not itself an ordinal switch. All active members of
one consensus cluster must therefore run the same jar and resolved configuration.

The behavioral switch is separately gated by
`snapshot.certified-consensus-activation-ordinal`. Operators install the aligned jar
and activation configuration on the full cluster before the announced future key.
Legacy logic remains active below it; the canonical reset and v35 state machine begin
at it. Crossing the key requires no second restart.

DAG L0 and each Currency L0 have independent snapshot ordinal spaces and independent
activation decisions. An absent environment entry resolves to disabled; local dev
activates at genesis. Public activation values must not be added until their rollout
keys are announced.

This ADR does not change `GlobalIncrementalSnapshot`, `GlobalSnapshotInfo`,
`GlobalSnapshotStateProof`, `CurrencyIncrementalSnapshot`, `CurrencySnapshotInfo`,
artifact hashing, state-proof calculation, or the meaning of artifact signatures.
Passive metagraph software that validates finalized global snapshots does not need to
understand the v35 consensus envelope. Active Currency L0 facilitators do need the
aligned v35 jar/config when their own metagraph activates it.

## Consequences

- The same artifact cannot be consumed as two different certified outcomes without a
  frozen-Core safety violation.
- Core remains the liveness/certification cohort; Tier 1 remains in broad artifact
  signing, finality, and reward participation.
- A finalizable zero-headroom committee can replace a hysteresis-qualified silent Core
  or Tier-1 seat with a Core-attested ReadyAtTip peer without first increasing Q(N).
  Replacement still requires the original frozen Core quorum and therefore cannot rescue
  a committee that has already fallen below that quorum.
- Two additional Core message phases add latency and traffic. IntegrationNet load
  validation must measure the EventTrigger p95 impact before public activation.
- A mixed v34/v35 active cluster is unsupported and is fenced before useful consensus
  progress. Pre-activation snapshot history remains replayable with legacy rules.
- Public snapshot and state-proof compatibility is preserved, but the active consensus
  message and local outcome/sidecar schemas change.
- Missing or corrupt certified sidecars reduce recovery availability and must never be
  treated as valid evidence; every adopted outcome is cryptographically re-verified
  against the locally known parent committee.
- This includes initial download: layer preflight runs before the newer-outcome
  application-storage shortcut, sidecar writes, vote-lock cleanup, or consensus CAS. A
  missing trusted predecessor rejects the handoff without mutation. Operators adding a
  fresh node after activation must provide an announced trusted checkpoint/recovery plan
  until contiguous certified-outcome download is implemented.
- Ordinary rollback at or after activation requires an operator-signed recovery plan. A
  standalone typed outcome cannot authenticate the lineage of the committee used to
  verify its own QCs; long-range ordinary rollback remains out of scope until a
  contiguous certified chain or separately trusted checkpoint is implemented.
- Recovery-plan format v1 accepts JSON-era incremental anchors only. Its single anchor
  hash cannot safely encode both a Kryo-era public parent identity and the local rollback
  locator, so every planned node rejects that historical case before joining or mutation.
- Missing and corrupt pre-finalization vote-lock records have deliberately different
  semantics: missing is no prior durable vote, while corrupt is a hard local safety
  failure. Operators must preserve the `certifiedVoteLocks` directory through ordinary
  process restarts and must not manually delete it to recover liveness.
- The durable post-finalization write is awaited before the command loop continues. Its
  latency is exposed by `dag_consensus_outcome_hook_duration_seconds`; public activation
  remains gated on IntegrationNet EventTrigger and hook p95 measurements.
- Certification guarantees one semantic `valueHash`, not one incidental proof envelope.
  Honest nodes may retain different valid artifact/QC signature subsets. Those subsets
  are verified and never feed the next deterministic state; byte-identical claims apply
  to the certified value and derived operational outcome, not necessarily the raw local
  sidecar file.
