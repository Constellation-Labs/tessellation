# Snapshot Streaming and Block Explorer reconciliation

This runbook defines how operators keep Snapshot Streaming (SS), its export stores, and
Block Explorer (BE) aligned with the canonical Global L0 lineage during an upgrade or
rollback. It distinguishes three operations that must not be conflated:

1. an ordinary no-reorg software upgrade;
2. a deliberate full SS replay/rebuild; and
3. repair of only the divergent suffix after a canonical Global L0 rollback.

The procedures are operationally destructive in different ways. Fill the approvals and
artifact identities below before executing one.

## Change record and authority

| Role/evidence | Required value |
|---|---|
| Incident/release owner | `<name>` |
| Global L0 operator approval | `<name/ticket>` |
| Snapshot Streaming owner approval | `<name/ticket>` |
| Block Explorer owner approval | `<name/ticket>` |
| Backup owner and location | `<name/immutable-location>` |
| Global L0 release/tag/checksum | `<value>` |
| SS source commit and artifact checksum/image digest | `<value>` |
| BE source commit and artifact checksum/image digest | `<value>` |
| Planned mode | `ordinary-upgrade`, `full-rebuild`, or `suffix-repair` |
| Canonical source peers | `<source-1>`, `<source-2>`, `<source-3>` |

No approval placeholder may remain blank when a deletion or reseed begins.

## Canonical source decision

In the operated three-source topology, SS queries all three controlled Global L0 sources.
Source selection is two-stage:

1. choose the plurality reported ordinal; then
2. require at least two of the three sources at that ordinal to report the exact same
   artifact hash.

Confirm that the deployed `l0Peers` set names exactly those three controlled sources;
changing the configured source count changes the hash threshold. A single responsive source
can steer the bare-plurality ordinal that is attempted, but it cannot satisfy the `2-of-3`
hash threshold.

The selected ordinal/hash is the candidate canonical watermark, not permission to skip
ordinary validation. SS must still fetch and validate the artifact, linkage, proofs, and
state transition through its normal SDK path before export. After that validation and export,
the watermark is the operated canonical-checkpoint boundary eligible for rollback selection.
A `2-of-3` source match does not make a malformed snapshot valid and is not a substitute for
Global L0's snapshot proof quorum.

Record:

```text
canonical ordinal:  <N>
canonical hash:     <H>
source 1:           <ordinal/hash/result>
source 2:           <ordinal/hash/result>
source 3:           <ordinal/hash/result>
observation time:   <UTC timestamp>
```

Abort and escalate when:

- no responsive configured source yields a candidate ordinal;
- fewer than two sources match on the exact hash at the selected ordinal;
- the matching artifact fails ordinary validation; or
- source identities or release/config fences are not the expected operated values.

Do not break a tie with BE, PostgreSQL, OpenSearch, S3, an SS local cache, or the numerically
highest ordinal. Those are derived consumers, not lineage authority.

## Required inventory before any mode

1. Stop automated restart/rebuild jobs for Global L0, SS, and BE for the duration of the
   operation.
2. Record the canonical source decision above.
3. Record each consumer's current watermark and hash:
   - SS `nextOrdinal` or equivalent resume marker;
   - last validated/exported Global ordinal and hash;
   - PostgreSQL highest ordinal and corresponding hash;
   - S3/object-store highest ordinal and corresponding hash;
   - OpenSearch/BE highest ordinal and corresponding hash.
4. Record table/index/object ownership and foreign-key dependencies. Never assume that
   deleting one ordinal table removes its dependent rows or object-store keys.
5. Back up the resume marker, SS configuration, databases, object manifests, and BE index
   metadata to an immutable or separately mounted location.
6. Verify the exact SS artifact was built against the exact Tessellation SDK selected for
   the network and that the configured environment and historical state-proof gates resolve
   correctly.

## Mode A: ordinary no-reorg upgrade

Use this mode only when the canonical lineage is unchanged and every exported ordinal/hash
through the recorded watermark still matches the sources.

1. Prove the existing exported watermark hash matches the canonical source lineage.
2. Stop SS cleanly and confirm no export worker remains active.
3. Stop or place BE ingestion in maintenance mode; read-only service may remain available if
   it cannot mutate the indexes being checked.
4. Preserve all resume markers and derived data. Do **not** clear PostgreSQL, S3, OpenSearch,
   or BE indexes.
5. Install the recorded SS artifact/config and start SS.
6. Require a startup log or health response proving the intended environment, SDK/gates,
   and resume ordinal were loaded.
7. Require SS to validate and export at least one new canonical successor.
8. Confirm PostgreSQL, object storage, and BE show that successor's exact canonical hash.
9. Resume normal BE ingestion and then re-enable alerting. Re-enable automated restart only
   after sustained forward progress; a flat tip caused by an explicit consensus alignment
   hold is not a restart signal.

Any hash mismatch changes the operation to Mode C. Do not overwrite the row and continue.

## Mode B: deliberate full replay/rebuild

Use this mode when a complete derived-state rebuild is explicitly intended: for example,
the SS schema or state-application logic cannot safely continue from the existing data.
This is not the default response to a suffix fork.

1. Obtain explicit SS and BE owner approval for a full rebuild and record the expected
   duration/capacity.
2. Stop SS and BE ingestion and prove all writers are stopped.
3. Take and verify restorable backups of SS-owned PostgreSQL data, object manifests/data,
   OpenSearch indexes, BE indexes, and the resume marker.
4. Reconfirm the canonical source decision immediately before deletion.
5. Delete or recreate only the explicitly inventoried **derived** SS/BE stores. Never delete
   Global L0 snapshot storage through this procedure.
6. Set the SS replay seed/resume marker to the agreed canonical root or zero, as required by
   the deployed SS release, and record the selected root ordinal/hash.
7. Start SS with the exact approved artifact and configuration. Keep BE ingestion stopped.
8. Require ordinary validation for every replayed artifact. Sample ordinal/hash agreement at
   the root, historical proof boundaries, recent history, and tip.
9. Do not release BE until SS reaches the agreed publication watermark and its PostgreSQL and
   object-store views agree on exact hashes.
10. Rebuild or reseed BE from that reconciled SS state; then compare BE's watermark/hash with
    SS and two matching Global L0 sources.
11. Resume ingestion and alerting. Retain backups until the acceptance window expires.

## Mode C: canonical rollback and divergent-suffix repair

Use this mode when Global L0 deliberately selects an ancestor and produces a replacement
lineage, while SS or BE may already contain rows/objects from the abandoned suffix.

Let `D` be the first ordinal at which an exported consumer hash differs from the newly
canonical lineage. If the consumer has only future ordinals beyond the selected ancestor,
`D = ancestor + 1`.

1. Stop SS before the Global L0 rollback lineage is installed or allowed to produce a
   replacement successor. Stop BE ingestion before its source data changes.
2. Record the old exported tip/hash, selected ancestor ordinal/hash, and expected first
   replacement ordinal `D`.
3. After Global L0 recovery, obtain the three-source decision and require a `2-of-3` exact
   hash at the new canonical watermark plus ordinary artifact validation.
4. Locate `D` independently in every derived store. Do not assume all stores stopped at the
   same ordinal.
5. With explicit SS/BE owner approval, remove or quarantine every **derived** row, object,
   and index document at or above `D`, including dependent rows and hash-keyed objects.
   Verify foreign-key cascades and unique-ordinal constraints before resuming writes.
6. Reset each SS resume/seed marker to the last common canonical artifact (`D - 1`) and its
   hash. If the deployed SS implementation cannot safely replay from that point, switch to
   the explicitly approved Mode B full rebuild instead of improvising a partial reset.
7. Start SS while BE remains stopped. Require SS to validate and reproduce the replacement
   suffix from `D` through the current canonical watermark.
8. Compare exact ordinal/hash values among SS PostgreSQL, object storage, and two matching
   sources at `D`, an interior sample, and the tip.
9. Remove/reseed the corresponding BE suffix and let BE ingest only the reconciled SS lineage.
10. Require BE, SS, and at least two sources to agree at the final watermark before reopening
    public indexing and automated monitoring.

Never rely on `ON CONFLICT` behavior to repair a fork implicitly. A uniqueness constraint on
ordinal or a hash-keyed upsert can preserve abandoned data, reject canonical replacements, or
produce internally inconsistent derived tables.

## Stop/delete/reseed/resume gates

| Gate | Pass condition |
|---|---|
| STOPPED | Every SS/BE writer and automated restart job is demonstrably stopped |
| AUTHORIZED | Named owners approved the selected mode and exact mutation scope |
| BACKED UP | Resume markers and every mutated store have a verified restorable backup |
| CANONICAL | Plurality ordinal, 2-of-3 exact source hash, and ordinary validation all pass |
| DELETED | Only the approved derived range/store was removed; canonical source storage is untouched |
| SEEDED | Resume/root marker names the recorded canonical ordinal and hash |
| REPLAYED | SS validates and exports the intended canonical range without gaps |
| RECONCILED | SS stores, BE, and two sources agree on exact ordinal/hash at the watermark |
| RESUMED | Ingestion progresses and alerts are healthy before restart automation is re-enabled |

If any gate fails, remain stopped and preserve evidence. Do not convert a failed suffix repair
into a full rebuild without a new approval.

## Evidence retained after the operation

Retain the change record, three-source responses, canonical artifacts sampled, backup
locations, deleted ranges, SQL/index/object manifests, resume markers before/after, process
health output, and final SS/BE/source watermark comparison with the release evidence bundle.
