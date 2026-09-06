# 34. Consensus schema change governance

Date: 2026-09-06

## Status

Proposed

## Context

Tessellation public networks adopt one software version through a coordinated full-cluster cold
restart. That operating model lets every validator change runtime consensus behavior together and
avoids mixed-version state machines. It does not erase or rewrite already signed history, and it
does not update Snapshot Streaming, Block Explorer, metagraph binaries, SDK consumers, or archived
artifacts.

Consequently, "the fleet cold-restarts" and "the change is schema-compatible" are independent
claims. A behavior change can be safe at a cold restart while a field, codec, hash preimage, state
proof, or historical state-transition change can still make external consumers halt or make old
history impossible to replay. Even an optional field is a schema change once producers emit it or
its presence changes signed bytes.

Schema impact has also been easy to miss when the edited file is outside a directory named
`schema`. A change to a Circe codec, canonical collection, JSON printer, hasher, state-proof
assembler, version projection, or acceptance function can alter consensus bytes or replay without
changing a case-class declaration.

## Decision

Every consensus-adjacent change must declare one of the following compatibility classes before it
is approved:

1. **Runtime-only behavior.** The change affects live scheduling, liveness, transport retries,
   in-memory coordination, proposal/leader/event selection, observability, or an equivalent
   internal mechanism. It may intentionally produce different future consensus outcomes. It does
   not change the representation or meaning of signed/hashable values, codec or hash/signature
   construction, persisted formats, public/P2P payload schemas, state-proof construction, or the
   deterministic interpretation of already-signed artifacts. A coordinated cold restart plus
   proportionate behavioral tests is sufficient.
2. **Replay or state-transition behavior with a stable schema.** The encoded type shape stays the
   same, but an old artifact could be accepted, rejected, applied, or hashed differently, or could
   derive a different state/root. Preserve the historical path and select the new behavior using a
   deterministic activation boundary. Test both sides and replay across the boundary. A cold
   restart alone is not sufficient.
3. **Schema or wire change.** The change can alter encoded bytes, hash/signature preimages, field
   presence or meaning, public/P2P payloads, persisted formats, state proofs, or SDK-visible
   consensus types. It requires an explicit schema design and cross-consumer rollout. It must not
   be introduced incidentally in a behavioral PR.

If the classification is uncertain, use class 3 until the affected surfaces are proven not to
change. File location is not evidence of compatibility.

### Schema-sensitive surfaces

The following are examples, not an exhaustive path allowlist:

- `GlobalSnapshot`, `GlobalIncrementalSnapshot`, `GlobalSnapshotInfo`,
  `GlobalSnapshotStateProof`, Currency snapshot variants, versioned historical projections, and
  signed consensus/QC/sidecar artifacts;
- Circe encoders/decoders, discriminator/default/null behavior, canonical collection ordering,
  `JsonSerializer`, Kryo registrations retained for historical reads, and binary codecs;
- every value used as a hash or signature preimage, plus Merkle/MPT key encoding and state-proof
  assembly;
- acceptance and context functions whose output must reproduce historical state or roots;
- P2P/HTTP payloads, routes, and persistence formats consumed across process or release boundaries;
- SDK and project-template types used by Currency L0/L1, Data L1, or specialized state channels;
  and
- Snapshot Streaming and Block Explorer decoding, validation, database projection, and replay.

Internal storage or behavior is not automatically safe: if it survives a release, controls replay,
or becomes part of an externally visible artifact, it belongs in the audit.

### Required evidence for runtime-only classification

The PR must identify why the change cannot affect:

- serialized type shape, canonical encoding, or hash/signature construction (future values and
  outcomes may differ as the stated purpose of the behavior change);
- historical acceptance, context reconstruction, state roots, or ordinal-gate selection;
- files or sidecars that must be read after restart or by another version; and
- Snapshot Streaming, Block Explorer, SDK, metagraph, or public API consumers.

Tests should demonstrate the relevant behavior without updating golden wire/hash fixtures. If a
golden fixture changes, the PR is not runtime-only until that difference is explained and approved.

### Required evidence for replay/state-transition changes

- Select behavior from artifact-carried ordinal/epoch context, never directly from
  `AppEnvironment` inside consensus logic.
- Every missing `FieldsAddedOrdinals` threshold mapping resolves to the disabled
  `SnapshotOrdinal.MaxValue` sentinel. Active-from-genesis behavior requires an explicit `0`.
  Any future exception requires an explicit per-gate rationale, source comment, and regression
  test rather than an ad hoc fallback at one consumer.
- Record the gate's ordinal/epoch domain, exact comparator (`>=`, `>`, or exact-key), missing/default
  semantics, and whether the resolved value is included in `deterministicConfigHash`.
- For new behavior, choose and announce a strictly future activation only after the release and
  consumer plan is approved. A replay correction may instead pin the exact historical cutover at
  which the corrected behavior already entered signed history, but only with retained chain/release
  evidence for that boundary.
- Preserve the old derivation below the boundary. Never move an already-crossed gate forward or
  reinterpret the signed interval behind it.
- Use the same selector in producer, validator, follower, download/recovery, and external
  re-derivation paths.
- Test `A-1`, `A`, and `A+1`, restart and rollback across `A`, and long-range replay beginning
  before `A`. Include state-root/hash assertions, not only successful decoding.

### Required schema-change package

A class-3 change blocks merge until the PR or a linked ADR records:

Every numbered item must be completed or marked not applicable with evidence. For example, an
internal P2P message can require wire-version and mixed-version analysis without changing Snapshot
Streaming, the SDK, or metagraph schemas; the review record must say why those consumers are not
affected rather than inventing unnecessary work for them.

1. the explicit human approval to change schema and why a runtime-only or stable-schema solution is
   insufficient;
2. the exact types, fields, codecs, canonical ordering, hash/signature preimages, persistence, and
   semantic meaning that change;
3. the old/new compatibility matrix for producers and every consumer, including historical
   decoding and replay;
4. a versioned or ordinal-gated transition whose old path remains capable of validating old
   history;
5. the exact Snapshot Streaming source, configuration, artifact digest, state-proof validation,
   and database/reindex or migration plan;
6. the exact SDK/project-template and active metagraph rebuild plan, including Currency L0/L1 and
   Data L1 where applicable;
7. Block Explorer/API compatibility and reconciliation expectations;
8. golden old/new serialization and hash fixtures, boundary replay, restart/rollback, fresh-node,
   and representative external-consumer tests; and
9. release notes, operator notice, activation gates, and a post-activation reconciliation plan.

An optional field or drop-null encoding may be a useful migration tool, but it does not waive this
package. Decoder compatibility alone is insufficient when hashes or signatures cover the encoded
value.

### Schema freeze

Once a release candidate, Snapshot Streaming artifact, SDK/metagraph rebuild, or activation notice
has been finalized against a schema, that schema is frozen. A later schema-affecting change requires
a new reviewed candidate and, where external operators or activation timing are affected, a new
artifact set and announcement. Do not silently amend the schema to finish unrelated correctness or
liveness work.

Behavioral fixes may continue during the freeze when their runtime-only or replay-compatible
classification is supported by evidence. The freeze protects interfaces and historical meaning,
not implementation details.

### Review record

Consensus-adjacent PR descriptions must contain a `Consensus compatibility` section with:

- the selected class and rationale;
- affected signed/persisted types and hash/state-proof paths, or evidence that there are none;
- replay and activation implications;
- Snapshot Streaming, Block Explorer, SDK, and metagraph impact; and
- the exact tests and external artifacts used as evidence.

The pull-request template exposes this declaration to humans and coding agents. Repository-level
`AGENTS.md` and `CLAUDE.md` both point to this ADR so Codex and Claude load the same authority rather
than maintaining divergent agent-specific rules.

## Consequences

- Full cold restarts remain available for frequent consensus behavior improvements without
  pretending that persisted and external interfaces disappear.
- Schema work becomes deliberate, separately reviewable, and coupled to the consumers that can
  otherwise halt or diverge.
- Some changes initially described as behavior-only will require an ordinal gate because they
  alter historical interpretation even though their case classes are unchanged.
- The classification and evidence add review work. That cost is intentional because a false
  schema-neutral claim can invalidate replay or strand external metagraph and indexing systems.
- Documentation and a PR declaration improve discovery but are not a complete mechanical guard.
  CODEOWNERS and broader golden compatibility fixtures should be added after the responsible owner
  or team and protected path set are explicitly selected; a path-only detector cannot find every
  semantic schema change.
