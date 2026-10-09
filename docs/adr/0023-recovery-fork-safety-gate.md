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
are unchanged. The Consequences above that describe a configured L2c no longer apply: a validly-signed minority fork remains
acceptable to a node that recovers by download, and fork choice on recovery is the operator's anchor selection.
