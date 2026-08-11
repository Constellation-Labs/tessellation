# ADR-0031: Certified consensus outcomes over a frozen round context

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
   accepted in preference to changing the safety universe mid-round.
8. At the exact activation key, discard legacy node-local controller/evidence,
   recent-signer/proof, penalty/probation, and PeerHistory windows before deriving the
   first v35 round. DAG seeds membership from the last signed artifact's
   `nextFacilitators`. Currency seeds from signed controller evidence and fails closed
   if no signed seed exists.
9. Persist the certified outcome beside the normal snapshot and support verification
   and adoption at the same consensus key. The sidecar and peer transport use the
   existing outcome/Circe infrastructure and are not fields in the public snapshot or
   state proof. Arbitrary long-range certification is out of scope until a certificate
   chain or trusted checkpoint is specified.
10. Make direct consensus delivery enqueue-only from the FSM's perspective, and make
    every soft reset schedule a replacement action. Network fanout latency must not
    block the serialized consensus command loop.

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
- Two additional Core message phases add latency and traffic. IntegrationNet load
  validation must measure the EventTrigger p95 impact before public activation.
- A mixed v34/v35 active cluster is unsupported and is fenced before useful consensus
  progress. Pre-activation snapshot history remains replayable with legacy rules.
- Public snapshot and state-proof compatibility is preserved, but the active consensus
  message and local outcome/sidecar schemas change.
- Missing or corrupt certified sidecars reduce recovery availability and must never be
  treated as valid evidence; every adopted outcome is cryptographically re-verified
  against the locally known parent committee.
