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

The deployed failure was in Global L0, whose large, geographically distributed
committee needs view changes and partial synchrony. Currency L0 serves a materially
different topology: small, permissioned, normally co-located metagraph cohorts. Extending
the complete v35 leader/view/QC protocol into Currency introduced an additional binary
agreement protocol and randomized-proof-envelope lineage without solving a production
requirement. Currency is therefore explicitly outside this ADR and retains a
Currency-local flat synchronous protocol derived from release/mainnet.

The existing Global artifact signature must retain its present meaning so snapshot and
state-proof validation remain compatible.

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
   helpers, while keeping Global artifact/context derivation explicit. Currency does
   not construct or consume these certificates.
4. Use explicit codecs only for the recursive QC-bearing message paths affected by
   the known Circe/Magnolia concurrent first-initialization defect. Those codecs use
   the repository's generic `Signed[A]` codec and preserve the ordinary case-class
   JSON field shape. Concurrent first-touch and golden-hash tests pin this boundary.
5. Freeze the round-start Core and Core+Tier-1 committee for the complete ordinal.
   Core prepares one exact `ProposalValue`, forms a BFT-supermajority ProposalQC,
   then forms a separately domain-separated Core commit QC. The frozen full committee
   continues to sign the unchanged artifact hash and satisfy the existing finality
   floor. Every v35 Facility also contains a transferably signed, domain-separated
   `TriggerStatement` binding layer, network, key, parent, frozen-committee hash,
   deterministic config hash, and trigger. The leader carries a valid leader-bearing
   Facility quorum and derives the trigger only from that evidence; followers verify
   the same evidence without consulting their local event-pacing state. Invalid inner
   statements are excluded, but the remaining set must still meet the unchanged
   Facility-phase quorum.
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
   serialization or hash domain. Currency L0's separate synchronous membership/ACK rules
   do not adopt this policy. These are separate policy capabilities: freezing N must not
   implicitly enable health-derived contraction.
8. At the exact activation key, discard legacy node-local controller/evidence,
   recent-signer/proof, penalty/probation, and PeerHistory windows before deriving the
   first v35 round. Global L0 seeds membership from the latest controller-evidence
   transition inside the signed legacy artifact's PeerHistory and fail closed if no
   signed seed exists. DAG's historical `nextFacilitators` field is a singleton
   compatibility field and is explicitly not an activation committee source. Because
   signed PeerHistory is constructed before its own outcome exists, this bridge delays
   the last legacy membership delta by one round rather than reading a fresher but
   node-local sidecar.
9. Persist the complete typed Global `ConsensusOutcome` beside the normal snapshot after
   (and only after) the last-outcome compare-and-set succeeds. The sidecar supports
   diagnostics and exact local rollback binding, but is never download or committee
   authority: its certificate does not authenticate every derived operational field,
   so download validation always replays from an independently authorized public root.
   Serve exact
   terminal outcomes through the existing authenticated
   `/consensus/specific/outcome` route. Two valid semantic value hashes fail closed.

   Make the public chain self-verifying from an independently authorized root. Every
   v35 incremental snapshot at N+1 carries N's `CertifiedOutcome`; the terminal N has no
   child yet, so its certificate comes from the authenticated terminal-outcome response.
   An ordinary downloader begins at the locally state-proof-validated A-1 artifact, or
   the canonical first incremental genesis root, then validates the contiguous signed
   incremental-artifact sequence root-to-tip. Each QC authenticates the exact full/Core
   authority that certified its round, the exact `nextRoundAuthority`, and a commitment to
   the post-round operational state. Historical verification uses the fixed BFT
   supermajority floor and consumes these certified transition effects directly; it never
   re-runs a new binary's selector, Core sizing, quorum fraction, seedlist, allowance, or
   collateral policy against an old round.

   The root and terminal still undergo complete artifact/context/state-proof validation.
   For an interior round, the child-carried QC authenticates the context hash and the
   retained signed incremental artifact supplies the artifact bytes and proof envelope;
   the large `GlobalSnapshotInfo` preimage is not needed. The verifier folds one predecessor
   and at most two public frames with `tailRecM`, installs nothing until the terminal
   outcome and its operational-state commitment match, and therefore uses O(1) live heap
   while ordinary logarithmic SnapshotInfo retention remains intact.

   Policy authentication is deliberately effect-based rather than release-name-based.
   SemVer, an assembly checksum, and `CL_VERSION_HASH` are operational join fences, not
   historical consensus authority. The current round's honest Core executes the one
   join-fenced policy implementation and certifies its resulting next authority. A later
   policy implementation can change that result without making old history depend on old
   executable code. A change to the certificate's safety semantics or signed field meaning
   still requires a new versioned schema and coordinated activation; it must not reinterpret
   v35 bytes.

   `GlobalSnapshot` and the unrelated genesis-era `CurrencySnapshot` remain unchanged.
   The retained incremental/QC chain is sufficient for v35 activation. A future
   independently announced, content-addressed certified checkpoint may bound long-range
   O(epoch) I/O, but it is an optimization rather than an activation dependency. Such a
   checkpoint must remain a separate versioned object paired with an ordinary combined
   incremental checkpoint, must not be embedded in either full snapshot, and cannot
   self-authenticate from a committee named only inside itself. The existing best-effort
   checkpoint `.meta` sidecar is not an authority channel.

   State-proof/file validation remains ordinal-selected. The reconstructed legacy outcome
   identity and newly reset activation value use the current hasher, exactly as live state
   creation does. Every public network is already in the JSON-serde era before this
   activation; v35 adds no Kryo registration, fallback, or replay contract.

   Two explicit uncertified roots are locally authoritative: certified-consensus genesis
   and an exact env-authorized recovery-seed anchor. Production persists only their
   canonical typed root shape. Genesis validation derives the committee from the locally
   accepted artifact's proof signers. Selected recovery nodes derive it from
   `CL_GL0_RECOVERY_SEED_COMMITTEE` only after exact public-anchor,
   seedlist/allowance/collateral, and all-member alignment checks. The first certified
   child uses the same bound-outcome path. Its ordinary fixed-floor QC binds the reset
   parent hash and complete recovery committee; R+2 carries that R+1 QC publicly.

   Recovery intentionally breaks prior-QC authority continuity. The independent authority
   is therefore the operated permissioned procedure: one controlled rollback lead, the
   env-selected source cohort, a full-fleet cold restart, canonical source-chain selection,
   and Snapshot Streaming reconciliation. A later downloader verifies the canonical public
   parent and complete recovery-committee QC, but does not re-apply today's mutable
   seedlist/allowance policy to that historical reset. The bytes alone cannot distinguish an
   operator-authorized reset from a colluding permissioned cohort's competing reset; source
   selection and out-of-band accountability are part of this network's explicit trust model.

10. Persist each local certified vote lock before emitting its `OutcomeVote`, and
    persist every verified QC advancement before it can influence gossip or commit
    progression. Global L0 uses the generic
    `CertifiedVoteLockPersistence[F, Key]` with the SnapshotOrdinal filesystem
    implementation and the repository `JsonSerializer`.
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

Global L0 is the only v35 certification domain. An absent environment entry resolves to
disabled; local dev activates at genesis. Public activation values must not be added until
their rollout keys are announced.

This decision changes the public Global incremental artifact schema: only
`GlobalIncrementalSnapshot` gains an optional `certifiedLineage`. Full snapshot types and
Currency incremental snapshots remain byte- and schema-identical. Below activation the
Global incremental field is absent and legacy drop-null JSON bytes remain unchanged;
at/after activation Global incremental artifact hashes commit to it.
No public-network activation, rollback, or replay crosses the retired Kryo boundary, and
new functionality carries no new Kryo registration, fallback, or compatibility contract.
`GlobalSnapshotInfo`, `GlobalSnapshotStateProof`, `CurrencySnapshotInfo`, state-proof
calculation, and the meaning of the existing artifact signatures do not change. The same
release also carries the separately gated Currency snapshot version transition in
[ADR-0033](0033-versioned-currency-snapshot-history.md). Consumers of public snapshot
schemas, including Snapshot Streaming and metagraph SDK builds, must compile and test
against the aligned SDK before activation. Active Currency stacks must upgrade before the
separate ADR-0033 global boundary because their signed Currency history semantics change,
not because Currency carries a v35 certificate.

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
- State-proof compatibility is preserved, but public snapshot artifacts, active consensus
  messages, and local Global outcome/sidecar schemas change at the coordinated boundary.
- Missing or corrupt certified sidecars can make an exact ordinary local rollback
  unavailable, but sidecars are never download authority. Every downloaded outcome is
  authenticated through the retained public lineage from an independently authorized root,
  and the terminal sidecar's operational-state preimage must match its QC commitment.
- Initial download validates the entire retained public artifact/certificate chain before the
  newer-outcome application-storage shortcut, sidecar writes, vote-lock cleanup, or
  consensus CAS. A missing or invalid root/interior artifact rejects the handoff atomically;
  pruned interior SnapshotInfo files do not.
- Ordinal-gated DAG activation authenticates the A-1 artifact envelope as well as its
  state proof before using signed controller evidence: exact requested/embedded ordinal,
  ordinal-selected signatures, unique seedlisted proof signers, and exact context/state-proof
  binding are mandatory.
- Ordinary rollback at or after activation can use a locally retained exact certified-outcome
  sidecar only when that sidecar still exists for the selected content anchor. Sidecar retention
  is logarithmic, so an arbitrary older content-selected rollback will usually require the
  env-only recovery path. A standalone typed outcome cannot authenticate the committee used to
  verify its own QCs; the public chain must be walked from an authorized root, or a separately
  announced checkpoint must be adopted through its dedicated path.
- The env-only recovery seed accepts only exact incremental anchors. Pre-activation
  recovery uses the ordinal-selected historical hash and must leave three legacy rounds
  to rebuild controller evidence; at or after activation, the first ordinary QC is formed
  at `R+1` and becomes publicly reconstructible only when `R+2` carries it.
- Missing and corrupt pre-finalization vote-lock records have deliberately different
  semantics: missing is no prior durable vote, while corrupt is a hard local safety
  failure. Operators must preserve the `certifiedVoteLocks` directory through ordinary
  process restarts and must not manually delete it to recover liveness.
- The durable post-finalization write is awaited before the command loop continues. Its
  latency is exposed by `dag_consensus_outcome_hook_duration_seconds`; public activation
  remains gated on IntegrationNet EventTrigger and hook p95 measurements.
- Certification guarantees one Global semantic `valueHash`, not one incidental proof envelope.
  Honest nodes may retain different valid artifact/QC signature subsets. Those subsets
  are verified and never feed the next deterministic state; byte-identical claims apply
  to the certified value and derived operational outcome, not necessarily the raw local
  sidecar file.
- Currency L0 deliberately keeps the stable flat synchronous agreement: every retained
  member contributes the exact artifact and binary signatures used in Finished. Different
  randomized proof bytes can safe-halt an equivocated phase but cannot become two completed
  all-member binaries. Currency uses no ProposalQC, certified lineage, or certified vote-lock
  journal.
- This simplification restores the supported metagraph lifecycle: one controlled
  rollback/genesis lead starts self-only and validators join through public download,
  four-successor observation, exact outcome validation, and registration. No community
  validator signs a recovery plan.
- Historical Global replay does not reinterpret old certified membership under a joiner's
  current seedlist, allowance, collateral, Core sizing, quorum fraction, or selection policy.
  Each QC authenticates the exact full/Core authority for the following round and commits the
  next operational state; historical verification consumes those signed effects at the fixed
  BFT safety floor. This closes the historical-policy blocker without making SemVer or a
  self-declared policy identifier into consensus authority. A future change to signed-field
  meaning or QC safety rules requires a new schema variant and coordinated activation.
