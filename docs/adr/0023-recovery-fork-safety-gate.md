# 23. Layered recovery / download fork-safety gate

Date: 2026-06-30

## Status

Accepted. Amended 2026-10-09: L2c (seedlist-signed recovery checkpoint) removed; see Amendment.

Supersedes ADR-0013 (Delayed Download) and ADR-0014 (Download for incremental snapshots).

## Context

Recovery download previously enforced NO signature or finality threshold -- only state-proof presence and chain linkage. That cannot distinguish the canonical chain from a *valid* minority-fork history, so a recovering node could adopt a fork. ADR-0014's recursive incremental-chain validation and ADR-0013's delayed-download timing both predate the leader-based consensus (ADR-0017) and the MPT state proof (ADR-0024).

Two natural gates were rejected or deferred:

- **Naive seedlist-majority finality gate -- rejected.** Production finalizes against a committee supermajority, and the committee can be a strict seedlist *minority* (e.g. 3-of-5). A seedlist-count gate therefore over-rejects legitimate snapshots and re-breaks recovery. A fixed absolute count fails the same way. Finality is committee-relative, not seedlist-relative.
- **L2a (re-derive the committee from the outcome chain, enforce supermajority) -- deferred.** This is the principled gate, but it is blocked by missing infrastructure: there is no outcome-history fetch API, and snapshots do not self-describe their committee (`nextFacilitators` is a vestigial hardcoded constant).

Separately, parallel recovery escalation caused a cascade collapse: all active peers escalated at once, all entered `Observing`, and all tried to download an ordinal nobody could still produce.

## Decision

Ship a layered gate, with the principled gate deferred:

- **L1 (always on):** crypto-verify every downloaded snapshot's signatures under the ordinal's signing hasher, plus seedlist membership and unique-signer checks, on BOTH the download and observe paths. L1 explicitly does NOT enforce a finality threshold -- the per-round committee is not reconstructable at recovery -- so it closes signature forgery and non-seedlist signers, but a validly-signed minority/Byzantine fork still passes L1.
- **L2c (opt-in, removed 2026-10-09):** a seedlist-signed recovery checkpoint, domain-separated over `network / ordinal / hash [/ cfg]`, requiring a seedlist threshold, rejecting duplicate signers, and requiring the downloaded chain to contain the exact `(ordinal, hash)`. Inert when `CL_RECOVERY_CHECKPOINT_PATH` is unset.
- **Anti-cascade:** recovery escalation (both the `MaxStalls` non-retriable path and the `QuorumInfeasible` retriable path) triggers `triggerRecoveryDownload` only when Ready peers are registered at strictly-higher keys (`peersAtHigherKey > 0`); otherwise it is a cluster-wide stall and the node keeps retrying.
- **L2a is deferred** to a follow-up, pending outcome-history persistence and a committee-self-describing snapshot.

## Consequences

- L1 (always on) closes acceptance of forged signatures, non-seedlist signers, and duplicate signers on the download and observe paths. A configured L2c pins recovery to the operator-trusted fork at the checkpoint `(ordinal, hash)` -- a separate trust domain from protocol finality, not a finality claim. Full committee-relative finality reconstruction is L2a, which is **deferred**: until then, a validly-signed minority fork remains acceptable on recovery whenever L2c is not configured.
- No cascade collapse from synchronized escalation.
- **Cost:** L2c is off by default, so there is no checkpoint protection unless an operator configures and distributes a checkpoint file; the principled gate (L2a) remains unbuilt, so recovery fork-safety is *partial* until then.
- Builds on ADR-0016 (committee/finality are consensus-relative) and ADR-0024 (state proof).

Mechanism reference: the design note tracked in the recovery-finality-gate work.

## Amendment (2026-10-09): L2c removed

L2c was never used operationally. Producing the checkpoint required coordinating a seedlist-majority signature by hand for
every recovery, which the operated clusters never did. GL0 recovery instead uses `run-rollback <hash>` with the operator-named
committee in `CL_GL0_RECOVERY_SEED_COMMITTEE` (see `docs/operations/global-l0-recovery-seed-committee.md`): the operator
selects the anchor `(ordinal, hash)` and the recovery cohort directly on the controlled source nodes.

The `RecoveryCheckpoint` type, its loader, the `recovery.checkpoint-path` / `CL_RECOVERY_CHECKPOINT_PATH` setting, the download
and observe enforcement sites, and the `recovery_checkpoint_fork` download outcome were deleted. L1 and the anti-cascade gate
are unchanged. The Consequences above that describe a configured L2c no longer apply, and fork choice on recovery is the
operator's anchor selection. What a downloading node still rejects depends on the ordinal:

- **Below certified activation** (pre-v35 history; on mainnet everything up to the cutover anchor `R`): only L1 applies
  (valid, unique, seedlisted signers). A validly-signed minority fork there remains acceptable, as it was with L2c
  unconfigured.
- **Ordinary certified segments at or after activation:** `GlobalCertifiedDownloadValidator` checks each round's
  committee against the previous QC's certified next-round authority, verifies each QC and the artifact proofs against
  that inherited committee's supermajority, and binds the terminal outcome. A minority-signed fork fails here.
- **Recovery boundaries at or after activation:** the validator walks back to the latest reset boundary and trusts the
  committee that boundary's QC declares (`publicRecoveryRoot`: at least three members and the exact persisted parent
  hash). It does not compare that committee with the seedlist, the prior committee, or the env seed, by design, so that
  later seedlist changes cannot invalidate canonical history. Authority for a reset comes from the permissioned recovery
  procedure: one lead node runs `run-rollback`, and the other source nodes run `run-validator`, all with the same
  `CL_GL0_RECOVERY_SEED_COMMITTEE`. A downloading node accepts any boundary that procedure certifies; it cannot
  independently tell an authorized reset from an unauthorized one. L2c never covered this case in practice because it
  was never configured.
