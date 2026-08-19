# Global L0 anchor-bound recovery plan

The Global L0 recovery plan is an emergency operator tool for a chain whose frozen signing
committee can no longer reach quorum. It is not an automatic failure detector and it is not a
replacement for protocol finality.

Use it only during a coordinated, full-fleet cold restart. Start exactly one controlled node with
`run-rollback`; start every other node with the normal `run-validator` command. The rollback lead
and every named committee member receive the same `--recovery-plan` (or
`CL_GL0_RECOVERY_PLAN_PATH`). The lead must use `run-rollback`; every other named member must use
`run-validator`.

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

The rc.8 generator's local pre-write growth check is intentionally IntegrationNet-specific and uses
GL0's 2/3 supermajority. The fraction is not added to the signed plan. Every starting node performs
the authoritative check against its effective `quorum-threshold-fraction`, so a plan generated for
IntegrationNet fails closed if reused under an incompatible quorum mode.

Startup fails closed unless all of the following hold:

- the signed protocol domain, format version, and network match;
- a rollback node is the designated lead, while a validator is a named non-lead member;
- the plan is signed exclusively by that lead and the signature is valid;
- at least two unique committee members are named;
- the named committee can certify its next seat under the deployed
  `quorum-threshold-fraction` (included in `deterministicConfigHash` and required equal fleet-wide;
  IntegrationNet's 2/3 mode permits this, while unanimity does not);
- every member is in the configured seedlist and, when configured, allowance list;
- the committee does not exceed the environment-resolved `snapshot.max-facilitator-count` used by
  the live facilitator selector;
- the command's rollback hash and loaded snapshot ordinal/hash exactly match the plan anchor; and
- every planned member satisfies collateral against the loaded anchor.

Recovery-plan format v1 accepts only a signed **incremental** snapshot as the rollback target. A
full-snapshot hash normally causes rollback to synthesize a new first incremental snapshot whose
hash is different from the supplied source hash; plan mode rejects that source immediately after
read-only source discrimination, before synthetic-incremental construction, detailed anchor and
collateral validation, one-shot receipt consumption, or rollback-storage mutation. Plan mode also
requires the exact incremental and its
same-ordinal snapshot-info file to be locally readable. Anchor/source and anchor-balance collateral
checks run before chain traversal can initialize snapshot storage, sync MPT state, or prune local
history. Ordinary rollback retains its existing full-snapshot and traversal behavior.

The lead signature is an operational authorization and tamper check. It does not certify protocol
finality. A community operator who deliberately creates a conflicting rollback remains capable of
forking their own node, just as they already can with an inappropriate `run-rollback` command.

## Initial outcome

Normal rollback continues to seed the committee from the anchor snapshot's proof signers and
restore its operational sidecar.

With a verified plan, the lead instead seeds `facilitators` and `eligibleFacilitators` from the
canonical sorted committee. It clears carried removal, penalty, probation, quality, score, tier,
recent-signer, and controller-evidence windows. This flush is required: otherwise a dead anchor's
sidecar can silently filter a member from the committee that the operator explicitly selected.
The real signed anchor snapshot and context are unchanged.

The recovery-plan committee size seeds the bootstrap proof-size classification. A three-member
plan therefore uses the normal post-bootstrap finality floor rather than inheriting a weaker
classification from an unrelated historical proof subset.

## First-round barrier

The plan arms a generation-bound local first-round gate before consensus daemons start on every
named node. Unlike the legacy rollback deferral, it has no maximum-delay escape and never counts
unrelated Ready peers. `TimeTick`, `EventTrigger`, direct `StartRound`, completion chaining, and
soft-reset retry cannot bypass it. Each named node polls only the other planned peer IDs and releases
its gate through the serialized command loop when every expected peer:

- is in the current cluster session;
- advertises `Ready` or `WaitingForReady`; and
- serves the exact seeded consensus outcome at the anchor key.

"Exact" here means the existing Cats `Eq[GlobalConsensusOutcome]`: value-semantic equality across the outcome's operational fields. The
existing `Eq[Signed[A]]` deliberately compares the signed value and ignores differences in the attached proof subset, so two peers that
hold the same signed artifact value with different valid proof subsets can align. A difference in committee, controller evidence, rewards,
or any other outcome value field does not align.

Planned validators can reach this state on a stopped chain: their normal download path is restricted
to named peers and accepts only the exact anchor outcome. `initFromDownload` independently re-hashes
the signed anchor, reconstructs the canonical synthetic outcome, checks collateral, consumes the
same signed plan, installs the outcome, and then joins the all-member barrier. The lead retains the
exact anchor outcome through an asymmetric first-round release so a slower member can still fetch N
after another member advances.

The all-member condition is exact for any accepted plan size: a six-member plan requires all six
members to install and serve the same outcome before any member may start the first round. The
minimum remains two because mutual attestation and leader rotation are not meaningful for a
singleton. This is a recovery viability floor, not a reward-population cap.

Rc.12's normal post-rollback `Q(N)` first-round synchronizer does not replace
this rule. A verified plan has higher startup precedence, continues to poll
every named member on the current attempt, and retains this exact all-member
condition. See
[Global L0 first-round alignment](global-l0-first-round-alignment.md).

Each node durably writes `<snapshotPath>/recoveryPlanReceipts/<planId>.consumed` with exclusive
create semantics. Reuse by the same receipt initialization is idempotent. A new initialization,
including an in-process application restart, rebuilds the in-memory receipt state and fails closed
when the durable receipt exists. A crash after file creation conservatively burns the authority
even if the audit payload is incomplete. Generate a new signed plan ID to retry a failed
coordinated operation; never delete a receipt to make an old authorization reusable.

If any planned node exits after consuming its receipt but before the first-round barrier releases,
do not restart that node with the consumed plan and do not continue the partially aligned
operation. Stop the planned cohort, re-confirm the common anchor and committee, generate a newly
signed plan with a fresh `planId`, distribute it to every planned member, and restart the
coordinated procedure from step 5 below. Keep every old receipt and plan in the incident record.
The fresh ID authorizes a new attempt; it does not make the earlier authorization reusable.

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
   release/configuration.
5. Generate and independently review the signed recovery plan.
6. Start the designated lead with `run-rollback <anchor-hash> --recovery-plan <file>`.
7. Start every other planned member with `run-validator --recovery-plan <file>`, then start the rest
   of the fleet with ordinary `run-validator` and no plan.
8. Do not run the plan on a second rollback node. Do not persist the recovery option into a generic
   restart command. “One-shot” is an operational contract: the node deliberately does not delete or
   mutate the signed authorization file, and a durable receipt makes a later process fail closed, so
   orchestration must remove the option after the authorized invocation.
9. Verify the lead logs the plan ID, exact anchor, exact committee, and alignment of every planned
   peer before the first round.
   If any planned process exits after receipt consumption but before that alignment completes,
   stop the planned cohort and restart step 5 with a fresh signed `planId`; never delete a receipt
   or restart only the failed process against the consumed plan.
10. Verify the first completed round contains the expected committee and a healthy proof margin.
11. Before re-enabling automatic restart or leave/fork recovery, perform a second coordinated
    full-fleet cold restart **without** `--recovery-plan`/`CL_GL0_RECOVERY_PLAN_PATH`. The initial
    processes captured their startup method, including the one-shot plan path; an in-process
    leave/fork restart would correctly fail closed on the consumed receipt. Select a post-recovery
    anchor whose snapshot proof signers themselves contain the intended viable, margined controlled
    base—ordinary rollback seeds from those proofs, so merely observing one threshold-only completed
    round is insufficient. Record the exact ordinal, hash, and proof PeerIds before stopping; for a
    three-controlled-node recovery, require a snapshot proved by all three controlled IDs. Use the
    normal startup procedure for this plan-free restart (one controlled `run-rollback` lead and
    every other node `run-validator`) and verify ordinary
    consensus resumes from that healthy lineage. Keep restart automation disabled until this
    verification passes.
12. Confirm snapshot-streaming resumes on the canonical hash at the first re-produced ordinal; do
    not treat process health alone as proof that its database followed the new lineage.
13. Remove the plan file/path from orchestration after recovery; retain the signed file in the
    incident record.

If any invariant fails, correct the anchor or plan and restart the coordinated procedure. Do not
weaken the checks or wait for a timeout escape; plan mode intentionally has none.
