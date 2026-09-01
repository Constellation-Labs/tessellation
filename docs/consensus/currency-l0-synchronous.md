# Currency L0 synchronous consensus

Currency L0 uses a Currency-local flat synchronous protocol derived from the stable
release/mainnet implementation. It does not use Global L0's Core/Tier-1/Witness tiers,
leader/view pacemaker, ProposalQC, timeout/view-change certificates, certified membership,
quorum shrink, or certified-outcome lineage.

This separation is intentional. Currency cohorts are small, permissioned, and normally
co-located. Global L0 is the large geographically distributed network that requires the
partially synchronous v35 protocol.

## Intentional differences from release/mainnet v3.5.28

The four phases, fixed-universe ACK thresholds, observation window, and registration handoff
come from the production-stable synchronous design. The v4 implementation retains these
bounded hardenings rather than reproducing old infrastructure literally:

- current `EventMempool`/event gossip with fixed request, response, concurrency, and deadline
  limits;
- immutable `(facilitators hash, parent artifact hash, parent binary hash)` attempt domains;
- retained phase effects, so a later phase cannot commit before the prior effect succeeds;
- new-intent event-trigger watermarks rather than total-backlog retriggering;
- admission bounded by incumbent ACK headroom and the configured flat committee cap;
- exact, unambiguous private-outcome corroboration and stale-key `409` re-anchoring;
- bounded successor observation retries and peer-ahead re-entry;
- exact GL0 `(ordinal, hash)` fee selection;
- prepared/committed publication outboxes and canonical-replacement drains; and
- current JSON hashing only. All public networks are beyond the retired Kryo boundary.

These are deliberate v4 behavior/storage hardenings. They do not add Core/Tier-1 tiers, views,
QCs, certified Currency lineage, or a second activation boundary.

## Operating topology

- Exactly one controlled node starts `run-rollback` or `run-genesis`.
- Every other Currency node starts `run-validator`.
- A deployment or recovery is a coordinated cold restart onto one advertised version.
- The rollback/genesis lead starts as the sole facilitator. Validators join through public
  download, successor observation, exact outcome validation, and registration.
- Mixed Currency consensus versions and multiple independent rollback leads are unsupported.
- The `--allow-solo-consensus` flag arms dormant-lineage publication refresh. It does not
  select the live committee.

## Attempt identity

For Currency key `K`, every phase declaration is bound to an immutable attempt domain:

```text
D = (hash(round-start facilitators), hash(parent artifact), parent binary hash)
```

A declaration from a stale parent or different round-start committee cannot occupy the
live declaration slot. Withdrawal remains authenticated self-only operator intent and can
remove only its sender.

## Round phases

### 1. Facilities

Every retained facilitator emits a Facility containing:

- a bounded sorted set of event hashes;
- observed registration candidates;
- an optional trigger; and
- the attempt domain.

The phase requires a Facility from every retained member. Events and candidates are unions
over that complete set, so one member cannot censor data advertised by another. The trigger
uses the repository's deterministic majority/tie ordering; an all-`None` set selects the
pinned `EventTrigger` default.

Before advertising an event, a member confirms through request-specific
`POST /events/ihave` that every other round-start facilitator already holds it. At most eight
probes run concurrently. Each peer has a five-second deadline; the aggregate deadline covers
every bounded-concurrency wave plus scheduling margin (16 seconds at the configured maximum
20-member committee), rather than racing all waves against one peer's budget. Missing/error
confirmation contributes an empty set and defers the event. It cannot be omitted safely:
different nodes can observe different responder subsets under asymmetric connectivity. The
ordinary all-member Facility/ACK protocol handles the unavailable member, and an otherwise-empty
round can still start. Existing event gossip transports the signed event body through
`/events/push` and `/events/iwant`.

The IWANT protocol is bounded to 128 requested hashes, 16 returned events, and 4 MiB of
encoded response. A single event that cannot fit that response is rejected at local and
remote intake. This transport envelope is separate from the authoritative configured
Currency state-channel binary limit:

```text
max-state-channel-snapshot-binary-size-in-bytes = 512000
```

The 20 MiB Global event-cutter value is not a Currency binary limit.

### 2. Proposals

Each member constructs an artifact from the same parent, complete Facility set, selected
trigger, resolved event union, retained committee, and exact GL0 history. Every named event
must exist locally before construction; a partial event set is never proposed.

Members exchange artifact hashes and the artifact values. After every retained member has
declared, each node applies the stable occurrence/hash ordering and validates a non-local
winner before selecting it.

### 3. Artifact signatures

Every retained member signs the selected artifact hash. The phase accepts exactly one valid,
unique, matching signature from every retained member. Those exact received proofs construct
one `Signed[CurrencySnapshotArtifact]`.

An equivocating member can send different randomized ECDSA signatures to different peers and
halt this all-member phase. It cannot make two different proof envelopes complete, because
each completed envelope requires every retained member and the later binary phase binds its
exact bytes.

### 4. Binary signatures

Every member constructs the same unsigned `StateChannelSnapshotBinary` from:

- the exact signed artifact produced by phase 3;
- the exact parent binary hash; and
- a deterministic fee derived from the artifact's signed GL0 `(ordinal, hash)` view.

The referenced GL0 state is fetched by exact ordinal and its hash is checked. A moving local
GL0 tip never selects the fee input.

Every retained member signs the exact binary hash. Finished requires one valid, unique,
matching binary signature from every retained member.

## Fixed-universe ACK removal

If a phase stops receiving declarations, the round locks. ACK voters report the exact
responders they observed for that phase. For frozen committee size `N`, each member is:

- kept by at least `(N + 1) / 2` ACK votes;
- removed by at least `N / 2 + 1` ACK votes; or
- inconclusive, leaving the round locked.

This yields:

| N | votes required to remove a missing member | tolerated missing members when survivors agree |
|---:|---:|---:|
| 1 | 1 | 0 |
| 2 | 2 | 0 |
| 3 | 2 | 1 |
| 4 | 3 | 1 |
| 5 | 3 | 2 |
| 6 | 4 | 2 |
| 7 | 4 | 3 |

No timeout guesses a smaller committee. Partial declaration delivery may give different
surviving majorities immutable, incompatible ACK observations. That is a deliberate
safe-halt requiring controlled recovery, not a condition the protocol resolves by changing
the authority set. In particular, an exact split at even `N` is intentionally terminal for
that attempt: ACK evidence is immutable, so re-arming the same cycle would only recompute the
same inconclusive result. The shared GL0 `re-stall-timeout`, `max-stall-cycles`, and
`max-round-duration` fields are hash-fenced compatibility fields, not Currency escape hatches.

The first declaration from one origin in the exact attempt domain is immutable. A malformed
same-domain declaration can therefore count as that origin having responded for ACK observation
while still failing semantic validation. An allowlisted faulty operator can use this to force a
safe halt; later replacement bytes from that origin are not allowed to rewrite history.

## Membership symmetry

For `R >= 2` retained incumbents, one Finished outcome carries at most `R - 1` candidates.
The controlled singleton may carry at most two candidates to form the normal three-member
shape. Candidate selection is re-capped after any same-round contraction.

Admissions are additionally bounded by the legacy flat-consensus
`snapshot.consensus.max-facilitator-count` (`20` in the shipped configuration):

```text
room = max(0, configured cap - retained incumbents)
admit <= min(ACK headroom, room)
```

The cap never ejects an incumbent. A deterministic cursor rotates a stable registration set
so a temporarily unlucky peer is not permanently starved.

The bound ensures retained incumbents can ACK-remove every newly carried candidate if all of
them fail before their first Facility. The singleton exception still requires at least one
of its two candidates to participate; if both fail, operator recovery is required.

## Finished and publication durability

Proposal derivation keeps both awaiting and rejected events because later Currency/GL0
state may make them valid, but moves their hashes to the active FIFO tail. This preserves
retry semantics without allowing one permanently invalid oldest entry to monopolize every
bounded Facility batch. It does not evict the entry or create consensus authority.

The finalization effect is ordered:

1. prepare a non-publishable exact-binary outbox receipt;
2. when dormant-lineage recovery is armed, prepare its deadline-bearing receipt;
3. persist the exact artifact and context, then apply data-application acceptance against
   that persisted artifact;
4. verify recovery artifact/context read-back and commit the recovery receipt;
5. commit the ordinary publishable receipt last;
6. run the post-consensus data-application callback;
7. remove only events present in the committed artifact; and
8. enqueue the exact signed binary as the final fallible action.

If persistence or data-application acceptance fails, both prepared receipts are aborted and
none of the later publication/event-clearing effects run.

The outbox is bounded to 4,096 entries and 128 MiB. It stores the exact randomized binary
proof envelope and is local durability, not consensus authority. GL0 confirmation removes
confirmed entries. Exact canonical GL0 `(Currency ordinal, binary hash)` confirmation also
closes entries that aged out of incremental retention.

Rollback and download are canonical-history replacement operations. They first disable
publication, drain in-flight sends, discard ordinary and recovery receipts, and only then
install replacement history. A stale local binary cannot be published after authority moves.

## Validator download and re-entry

A validator:

1. downloads and validates a public Currency snapshot and context;
2. observes four sequential public successors;
3. requests the exact current private outcome from responsive Ready artifact-proof signers
   and requires the ACK-minimum cohort (`ceil(N/2)`) to serve one exact typed value, with no
   competing group at that bound;
4. verifies exact key, artifact value and proof bytes, context, signatures, committee hash,
   disjoint membership, and authorization as incumbent or selected candidate; and
5. atomically installs that outcome and starts the next generation.

Only the current exact outcome is served. A stale request receives `409 Conflict` and the
validator re-anchors. A missing/incompatible successor has a bounded retry budget; exhaustion
returns the node to `WaitingForDownload` so it can select a newer public anchor.

A live member excluded by Finished commits the common result, clears its private authority,
and re-enters download. A partitioned N=2 member may use its sole authenticated authority peer
only to trigger this local download transition. That observation cannot install authority;
the full public/exact-outcome validation remains mandatory.

The subsequent private-outcome handoff is Byzantine-safe only once the public artifact has at
least three proof signers. For N=1 or N=2 its `ceil(N/2)` corroboration bound is one response:
that is explicit permissioned-operator trust, not quorum certification. A malicious sole
responder that double-signs can give two honest nodes different next-round private committees
and thereby create competing Currency lineages. Healthy production operation therefore grows
to at least three trusted signers before treating the cohort as fault-tolerant.

## Failure expectations

| Condition | Result |
|---|---|
| one missing member at N >= 3, survivors agree | strict-majority ACK removes it |
| one missing member at N = 2 | safe halt |
| no surviving strict majority | safe halt |
| incompatible/partial ACK evidence | safe halt |
| malformed immutable same-domain declaration | safe halt; controlled recovery if the sender does not recover coherently |
| missing event bytes | proposal waits; event may be deferred next attempt |
| invalid artifact or binary signature | signature does not count; no finalization |
| exact GL0 fee context missing/mismatched | affected node fails closed |
| artifact/context persistence rejected | no publication or mempool clearing; node enters download reconciliation while the causally ordered effect retries with capped backoff |
| both singleton candidates fail | safe halt |
| restart after an N-signer artifact contracted to exactly `ceil(N/2)` binary signers | exact private handoff may lack enough remote corroborators; re-anchor and, if no public successor can authorize re-entry, use controlled rollback recovery |
| malicious allowlisted member equivocates at N >= 3 without controlling the handoff cohort | may halt; cannot create two honest all-member finalizations |
| malicious sole private-outcome responder at N <= 2 | can create competing lineages by double-signing; permissioned bootstrap/recovery trust residual, not BFT safety |

These holds are not crash signals and must not trigger uncoordinated single-node restart
automation. A controlled recovery stops the complete Currency cohort, starts exactly one
rollback lead, and starts every other member as validator.

## Activation and compatibility

The synchronous engine is a wire/behavior change selected by the coordinated release and
advertised version gate. It is not a new Currency snapshot field and needs no independent
v35 ordinal.

Currency protocol `1.0.0` is separate. Its signed deterministic-history semantics begin at
the announced Global ordinal from ADR-0033. Active metagraph Currency L0, Currency L1, and
Data L1 applications must be rebuilt and deployed before that boundary. No new functionality
supports the retired Kryo era.

## Primary signals

- current key, phase, round-start and retained facilitator counts;
- missing declarations and ACK keep/remove/inconclusive results;
- selected and final re-capped candidates;
- event availability confirmed/deferred outcomes;
- `dag_currency_consensus_persistence_reanchor_total` plus retained-effect warning attempts/delay;
- exact-outcome corroboration outcome (`success`, `no_responsive`, `different_key`,
  `invalid`, `under_threshold`, or `ambiguous`) together with proof-signer,
  responsive-Ready, served, valid, threshold, maximum-matching, and
  distinct-value gauges;
- exact-outcome mismatch, Conflict, and re-anchor;
- self-exclusion and peer-ahead download transitions;
- outbox prepared/committed entries and bytes;
- recovery receipt deadline/confirmation state; and
- publication drain, canonical replacement, and canonical mismatch outcomes.
