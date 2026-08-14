# Global L0 anchor-bound recovery plan

The Global L0 recovery plan is an emergency operator tool for a chain whose frozen signing
committee can no longer reach quorum. It is not an automatic failure detector and it is not a
replacement for protocol finality.

Use it only during a coordinated, full-fleet cold restart. Start exactly one controlled node with
`run-rollback`; start every other planned committee member with `run-validator`. Every planned
member receives the same signed file through `--recovery-plan` (or
`CL_GL0_RECOVERY_PLAN_PATH`). Validators outside the planned committee omit the option. The role is
part of startup validation: the designated lead cannot consume the validator path and no other
member can consume the rollback-lead path.

**DANGER — controlled-node contract:** every PeerId named in `committee` must launch with the
identical signed plan. The first-round barrier is deliberately local and adds no wire schema. A
named validator launched without the option can download and serve the synthetic anchor but has no
local start gate; if quorum-many named nodes are misconfigured this way, they can begin consensus
before the intended all-member barrier. Unplanned validators do not create this risk because they
are outside the seeded committee. Treat identical-plan installation and gate-held verification on
every named node as a mandatory operator precondition, not an advisory step.

The option is inert when absent. The recovery-plan mechanism changes no consensus message,
snapshot, state proof, or metagraph-facing schema, and it needs no activation ordinal. Rc.8 as a
release separately extends and enforces the internal `deterministicConfigHash` fingerprint; that
join fence is not signed into snapshots and does not alter state-proof calculation. The plan does
change the operator-authorized initial outcome at the rollback anchor, so the release must still
use a new immutable version and both normal fleet-wide join fences.

`versionHash` is the hash of the advertised version string (or the explicit `CL_VERSION_HASH`
override); it is **not** the jar/assembly hash and it is **not** `deterministicConfigHash`. The two
fences are independent and both are required. Tag this bridge with a distinct immutable release
version, or set one reviewed `CL_VERSION_HASH` across the entire fleet. Reusing rc.7's advertised
version would let behaviorally different jars join.

## Plan format and authority

The file is JSON for the existing `Signed[Gl0RecoveryPlan]` type:

```text
Gl0RecoveryPlan(
  protocol = "gl0-recovery-plan-v1",
  formatVersion = 1,
  planId,
  anchor = RecoveryCheckpoint(network, ordinal, snapshotHash),
  lead,
  committee = SortedSet(peerIds...)
)
```

The explicit `protocol` value is mandatory domain separation for the generic JSON hash; startup
checks it byte-for-byte before accepting the plan. Use the repository tool below: it creates the
value with the exact anchor and canonical committee, signs it once with the designated lead's
existing node key using `Signed.forAsyncHasher`, decodes and verifies the result, and writes a new
file atomically. Do not hand-construct signature bytes or invent a second hashing format.

```bash
CL_KEYSTORE=/secure/lead-node.p12 \
CL_KEYALIAS=alias \
CL_PASSWORD='...' \
sbt "tools/run generate-gl0-recovery-plan \
  --network Integrationnet \
  --ordinal 5884500 \
  --snapshot-hash <64-lowercase-hex-anchor-hash> \
  --plan-id <64-lowercase-hex-incident-id> \
  --committee-peer <128-lowercase-hex-lead-peer-id> \
  --committee-peer <128-lowercase-hex-peer-2> \
  --committee-peer <128-lowercase-hex-peer-3> \
  --output /secure/recovery-plan.json"
```

The output path must not already exist; the generator publishes it with an exclusive atomic link and
will not replace a racing writer's file. Keep the plan file and the command's non-secret arguments
in the incident record. Supply the password through the environment only; do not put it in shell
history or the incident record.

The generator's local pre-write growth check uses GL0's 2/3 supermajority. The fraction is not added
to the signed plan. Every starting node performs the authoritative check against its exact effective
`quorum-threshold-fraction`, so a plan generated for IntegrationNet fails closed if reused under an
incompatible quorum mode.

Startup fails closed unless all of the following hold:

- the signed protocol domain, format version, and network match;
- `planId` is exactly 64 lowercase hexadecimal characters;
- the local rollback node is the designated lead and is in the committee, or the local validator
  is a non-lead member of the committee;
- the plan is signed exclusively by that lead and the signature is valid;
- at least two unique committee members are named;
- the named committee can certify its next seat under the deployed
  `quorum-threshold-fraction` (included in `deterministicConfigHash` and required equal fleet-wide;
  IntegrationNet's 2/3 mode permits this, while unanimity does not);
- every member is in the configured seedlist and, when configured, allowance list;
- the committee does not exceed the environment-resolved `snapshot.max-facilitator-count` used by
  the live facilitator selector (not the distinct legacy controller-sizing scalar);
- the command's rollback hash and loaded snapshot ordinal/hash exactly match the plan anchor; and
- every planned member satisfies collateral against the loaded anchor.

The two-member minimum is a coordination floor, not a reward-population cap. The separate
next-seat viability check proves that the recovery committee can satisfy the configured `Q(N+1)`
headroom rule. In particular, unanimity mode cannot prove an unseated next signer and needs an
independently designed membership transition rather than a larger plan.

The plan is code-enforced one shot. After all static, exact-anchor, and collateral preflights pass,
but before rollback traversal, journal pruning, or sidecar mutation, the node writes a durable
receipt outside the rollback cleanup tree. An exact retry in the same process is idempotent. A new
process that finds the receipt fails closed. A partially written receipt also fails closed: incident
authority must never become reusable merely because receipt publication was interrupted. Never
delete a receipt to make an old plan reusable; generate and independently review a new plan ID.

The rollback lead's internally configured cluster-leave/fork restart retains `--recovery-plan`.
It therefore cannot silently restart as an ungated ordinary validator before barrier release: the
lead-role check and durable consumed receipt make a fresh process fail closed. After the planned
recovery has completed, remove the plan argument and return the node to the ordinary deployment
procedure.

If the anchor predates the configured v35 activation ordinal `A`, it must be at or before `A-3`.
The artifact at `K` carries operational evidence for `K-1`, so anchors at `A-1` and `A-2` cannot
provide enough signed history to enter the certified epoch canonically and are rejected. A plan
anchor at or after `A` is an explicitly authorized fresh certified epoch: it installs the plan's
canonical synthetic anchor outcome and deliberately removes same/above certified sidecars and
locks.

An unplanned ordinary rollback at or after `A` is rejected, including an anchor exactly at `A`.
A single typed outcome can prove its value and signatures but cannot independently authenticate the
lineage of the committee that signed it: after activation, the target artifact carries the parent
evidence used to derive that same target committee. Treating the node-local sidecar as independent
authority would therefore be circular. Restoring ordinary certified-epoch rollback requires a
future contiguous certificate chain or a separately trusted checkpoint; until then, use a newly
signed recovery plan. Certified sidecars remain transport for same-key live convergence, where the
node already knows the parent and frozen committee.

Recovery-plan format v1 accepts only a signed **incremental** snapshot as the rollback target. A
full-snapshot hash normally causes rollback to synthesize a new first incremental snapshot whose
hash is different from the supplied source hash; plan mode rejects that source explicitly rather
than adding a second anchor interpretation. Plan mode also requires the exact incremental and its
same-ordinal snapshot-info file to be locally readable. Anchor/source and anchor-balance collateral
checks run before chain traversal can initialize snapshot storage, sync MPT state, or prune local
history. Pre-activation ordinary rollback retains its existing full-snapshot and traversal
behavior; at or after `A`, the signed-plan requirement applies to incremental and full-snapshot
sources alike.

The lead signature is an operational authorization and tamper check. It does not certify protocol
finality. A community operator who deliberately creates a conflicting rollback remains capable of
forking their own node, just as they already can with an inappropriate `run-rollback` command.

## Initial outcome

Pre-activation ordinary rollback continues to seed the committee from the anchor snapshot's proof
signers and restore its operational sidecar.

With a verified plan, the lead instead seeds `facilitators` and `eligibleFacilitators` from the
canonical sorted committee. It clears carried removal, penalty, probation, quality, score, tier,
recent-signer, and controller-evidence windows. This flush is required: otherwise a dead anchor's
sidecar can silently filter a member from the committee that the operator explicitly selected.
The real signed anchor snapshot and context are unchanged.

The recovery-plan committee size seeds the bootstrap proof-size classification. A three-member
plan therefore uses the normal post-bootstrap finality floor rather than inheriting a weaker
classification from an unrelated historical proof subset.

## First-round barrier

The plan uses a special local first-round policy. Unlike the legacy rollback deferral, it has no
maximum-delay escape and never counts unrelated Ready peers. The same generation-bound gate is
armed on the rollback lead and every planned validator before consensus daemons start. Each planned
node polls only the planned peer IDs and releases its gate when every expected peer:

- is in the current cluster session;
- advertises `Ready` or `WaitingForReady`; and
- serves the exact seeded consensus outcome at the anchor key.

"Exact" here means the existing Cats `Eq[GlobalConsensusOutcome]`: value-semantic equality across the outcome's operational fields. The
existing `Eq[Signed[A]]` deliberately compares the signed value and ignores differences in the attached proof subset, so two peers that
hold the same signed artifact value with different valid proof subsets can align. A difference in committee, controller evidence, rewards,
or any other outcome value field does not align.

Planned validators can reach this state on a stopped chain: the existing stable-tip download
shortcut accepts the single Ready rollback lead only when its exact ordinal and hash match, then
`initFromDownload` installs the lead's outcome before entering `WaitingForReady` or `Ready`.

The gate covers all three ordinary round-start commands (`StartRound`, `TimeTick`, and
`FacilitateByEvent`), completion chaining, and soft-reset retry—not just the TimeTrigger path.
Release is serialized on the consensus command loop and binds the exact anchor key plus a monotonic
local generation; a stale release from an earlier initialization cannot open a newly armed gate.
The gate remains held until the serialized command has established the exact `N+1` first round with
the expected committee. A failed or cancelled establishment leaves the permit pending for retry;
telemetry is best-effort and cannot strand the gate. There is no arbitrary plan-size cap: every
named member is part of the exact all-member barrier. A missing or mismatched member therefore
keeps every planned node held, regardless of whether a quorum subset happens to be online.

## Runbook

1. Stop restart automation and stop the entire IntegrationNet fleet.
2. Select the anchor by ordinal, hash, state proof, and snapshot content—not by recency alone.
3. If the rollback re-produces ordinals already indexed by snapshot-streaming, identify the last
   shared `(ordinal, hash)` and the first divergent ordinal before restart. Snapshot-streaming's
   `global_snapshots.ordinal` uniqueness means stale fork rows can block canonical re-indexing even
   though snapshot hashes differ. Dry-run, independently review, and transactionally remove only
   rows at or above the proven first divergent ordinal (with FK-cascade effects understood), or
   re-seed the indexer from the shared anchor. The Aug-13 incident's internal reference procedure is
   `.workspace/gl0-recovery/ss-drop-fork-rows.sh`; never reuse its hard-coded ordinal or database
   target without re-deriving the boundary.
4. Confirm the planned nodes are controlled, reachable, collateralized, and on the same immutable
   release/configuration. Record one identical advertised `versionHash` and one identical exact
   effective `deterministicConfigHash` for the full fleet. Install the byte-identical signed plan
   only on every named member; compare the file checksum before startup.
5. Generate and independently review the signed recovery plan.
6. Start every non-lead planned member first with `run-validator --recovery-plan <file>`. Before
   starting the lead, collect the `Gl0RecoveryPlan VERIFIED` record from **every** named validator
   and require the same `planId`, anchor, committee size, and the message that the first round will
   be held. Also require `dag_consensus_first_round_start_gate_held = 1` on each named validator.
   Absence from even one planned member is a stop condition; an aligned anchor response does not
   prove that peer loaded the plan.
7. Start the designated lead last with
   `run-rollback <anchor-hash> --recovery-plan <file>`. Confirm its own `VERIFIED` record carries the
   same `planId` and that its held-gate gauge is `1` before accepting any first-round activity.
   Start unplanned validators without the option; they remain outside the seeded committee.
8. Do not run the plan on a second rollback node. Do not persist the recovery option into a generic
   restart command. Code consumes a durable one-shot receipt; orchestration must still remove the
   option after the authorized invocation so an automatic restart fails visibly rather than
   repeatedly attempting incident recovery.
9. Verify the lead logs the plan ID, exact anchor, exact committee, and alignment of every planned
   peer before the first round. Require the held-gate gauge to move from `1` to `0` only after the
   `exact_planned_committee_aligned` record.
10. Verify the first completed round contains the expected committee and a healthy proof margin.
11. Confirm snapshot-streaming resumes on the canonical hash at the first re-produced ordinal; do
    not treat process health alone as proof that its database followed the new lineage.
12. Remove the plan file/path from orchestration after recovery; retain the signed file in the
    incident record.

If any invariant fails, correct the anchor or plan and restart the coordinated procedure. Do not
weaken the checks or wait for a timeout escape; plan mode intentionally has none.
