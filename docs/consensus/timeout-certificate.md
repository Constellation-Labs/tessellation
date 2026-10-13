# Timeout Certificate (Track 2 view advance)

**Status:** As-shipped reference (release/testnet). Wired and active.

The Timeout Certificate (TC) is a HotStuff-aligned, quorum-certified view-advance
mechanism. It runs alongside the older `ViewChangeCertificate` (VCC) path: a stalled
round emits *both* a `ViewChangeVote` (Track 1, VCC) and a signed `TimeoutVote`
(Track 2, TC), and whichever certificate assembles first deterministically advances
the round's view. A formed TC carries no artifact-only `ProposalQC` (`highestKnownQc` is
always `None`, see section 1); cross-view safety travels in `CertifiedVoteLock`. It is re-validated against the same
quorum / voter-pool / hash invariants both when it assembles locally and when it
arrives embedded in a leader's `Proposal`. A view greater than 0 must be justified by
exactly one of a VCC or a TC; the two are mutually exclusive on any single proposal.
This document describes the TC pipeline and how it wires into the round FSM and the
proposal-acceptance path. For Track 1 (VCC) see [README section 10](README.md#10-leader-election--view-changes).

## 1. Two tracks, one trigger

Both tracks are driven from the same place. When `StallDetector` decides a round must
rotate its leader, it calls `ViewChangeManager.performViewChange(key, state, timeoutReason)`
(`engine/ViewChangeManager.scala:80`). That single method emits both votes and queues
both assembly checks:

```scala
voter.emitViewChangeVote(key, fromView, toView, highestKnownQc) >>        // Track 1 (VCC)
  timeoutVoter.emitTimeoutVote(key, fromView, toView, highestKnownQc, timeoutReason) // Track 2 (TC)
...
queue.offer(ConsensusCommand.CheckViewChangeAssembly(key)) >>
  queue.offer(ConsensusCommand.CheckTimeoutCertificateAssembly(key))
```

(`engine/ViewChangeManager.scala:100-106`.)

`fromView = state.viewNumber`, `toView = fromView + 1`. `highestKnownQc` is always
`None` (`ViewChangeManager.emitUnlocked`): the artifact-only `ProposalQC` carried by
pacemaker votes was a pre-v35 authority and was retired with the legacy engine
(ADR-0035 / #1627). Certified cross-view safety travels in the verified
`CertifiedProposalQC` held by `CertifiedVoteLock` instead. The view
change does **not** mutate `state.viewNumber` / `state.leader` locally; only an
assembled-and-applied certificate advances the round.

All in-tree callers of `performViewChange` use the default `timeoutReason =
TimeoutReason.NoProgress` (`engine/ViewChangeManager.scala:83`). The
`TimeoutReason` ADT also defines `QuorumInfeasible`, but no call site passes it today
(`declaration.scala:166-185`). Votes are grouped and certified per reason, so the
reason is part of the certificate's identity.

## 2. The timeout vote

`TimeoutVoter` is the emit interface (`engine/TimeoutVoter.scala:7-15`); the live
implementation is `GossipingTimeoutVoter` (`engine/GossipingTimeoutVoter.scala`).
`emitTimeoutVote`:

1. Reads round state; if there is no state for `key` it logs `skipped=no_state` and
   does nothing.
2. Signs a `TimeoutVote` over:

   ```scala
   case class TimeoutVote(
     fromView: Long, toView: Long,
     facilitatorsHash: Hash, lastSnapshotHash: Hash,
     highestKnownQc: Option[ProposalQC],
     reason: TimeoutReason
   )
   ```

   (`declaration.scala:188-195`.) `facilitatorsHash` is the **canonical round-start
   committee hash** (`state.roundStartFacilitators.value.hash`), not the live
   `state.facilitators` hash, so honest nodes that observed different mid-round
   withdrawals still sign the same hash and certify together. `lastSnapshotHash` is the
   round's last-finished snapshot hash (`lastSnapshotHashOf(state.lastOutcome)`).
3. Stores its own vote locally (`storage.addTimeoutVote`) and pushes the signed vote
   directly to the active facilitator set (`gossip.spreadDirect`, targets =
   `state.facilitators.value - selfId`).

(`engine/GossipingTimeoutVoter.scala:34-81`.)

Inbound timeout votes from peers arrive as `ConsensusPeerTimeoutVote`. `RumorHandler`
stores them (`storage.addTimeoutVote(origin, key, fromView, toView, signedVote)`) and
queues `CheckTimeoutCertificateAssembly(key)` (`state/RumorHandler.scala:200-203`).

## 3. Assembly: votes -> TimeoutCertificate

`CheckTimeoutCertificateAssembly(key)` is dispatched by the FSM
(`state/ConsensusFSM.scala:89`) to `StateTransitions.checkTimeoutCertificateAssembly`.
It is one of the "always-handled" assembly checks,
so it runs in both IDLE and BUSY (the same pattern that lets a VCC assemble while the
FSM is mid-phase on the same round). Assembly:

1. Reads the timeout votes stored under `(fromView, toView)`, where
   `fromView = state.viewNumber`, `toView = fromView + 1`.
2. Groups them by `reason` and processes each reason group independently.
3. For a reason group, all votes must agree on a single `facilitatorsHash`. Multiple
   distinct hashes log `timeout_divergent_facilitators_hash` and are not certified.
4. Computes the required quorum with `certificateQuorum(state, votes)`: only single-signer
   votes from members of the frozen `roundStartFacilitators` count, and the required count
   is the one committee quorum (`max(1, ceil(n * quorumThresholdFraction))`). There is no
   shrink (see [section 6](#6-quorum-is-never-lowered)).
5. Calls `TimeoutCertificateBuilder.build(...)` (`engine/TimeoutCertificateBuilder.scala`).

`TimeoutCertificateBuilder.build` validates, in order:

- every vote's `facilitatorsHash` matches the expected hash
  (`FacilitatorsHashMismatch`),
- every vote's `lastSnapshotHash` matches (`LastSnapshotHashMismatch`),
- every vote's `reason` matches (`ReasonMismatch`),
- only signers in the **voter pool** are counted; deduplicated by signer
  (`bySigner` keeps one vote per signer),
- the number of distinct in-pool signers is at least `quorumSize` (`UnderQuorum`),
- the carried `highestKnownQc`s do not diverge: if two QCs at the same view name
  different proposal hashes, the build fails (`DivergentQcs`).

On success it returns `TimeoutCertificate(fromView, toView, facilitatorsHash,
lastSnapshotHash, reason, votes)` (`declaration.scala:247-254`).

The voter pool passed to the builder is the frozen round committee
(`roundStartFacilitators`). The VCC builder uses the same pool; the B1/B2 builders use
the committee minus the target. The earlier wider witness pool (eligible-but-not-active
and quality-proven historical peers) was removed with the tier model
([ADR-0036](../adr/0036-single-frozen-voting-set.md)).

When the build succeeds, the FSM:

1. Calls `storage.markTimeoutCertificateApplyScheduled(key, lastSnapshotHash, fromView,
   toView)`, which returns `true` only the first time for a given `(lastSnapshotHash,
   fromView, toView)` transition under that `key` (subsequent assemblies are
   `duplicate_suppressed`).
2. Stores the certificate (`storage.storeTimeoutCertificate`).
3. After a `config.viewChangeApplyDelay` sleep, queues
   `CheckTimeoutCertificateApply(key, fromView, toView)`.

(`StateTransitions.checkTimeoutCertificateAssembly`.) The apply delay gives the symmetric VCC path a
chance to converge on the same view advance rather than racing.

## 4. Apply: advancing the view

`CheckTimeoutCertificateApply(key, fromView, toView)`
(`state/ConsensusFSM.scala:90`) routes to
`StateTransitions.checkTimeoutCertificateApply`,
which short-circuits to a stale outcome if the round already finished or
`state.viewNumber != fromView` (the VCC path or another TC already advanced). Otherwise
it loads the stored certificate and calls `applyCertifiedTimeoutCertificate`.

Apply **re-runs** `TimeoutCertificateBuilder.build` against the freshly recomputed
quorum and voter pool, so an apply never trusts the stored certificate blindly. If the
certificate still validates, the transition:

1. Keeps the round's membership. Global L0 runs the `RetainSigningLeases` membership
   policy: the active facilitator view is canonicalized back to the frozen
   `roundStartFacilitators`, and the TC voters never shrink it.
2. Selects the new leader with the round-robin schedule over the frozen committee:
   `LeaderSchedule.leader(roundStartFacilitators, ordinal, toView)`.
3. Atomically advances the round under a `condModifyState` guard that only fires when
   `s.viewNumber == fromView` (so a concurrent VCC/TC apply can win the race without
   double-advancing): sets `viewNumber = toView`, sets the new `leader`, resets
   `status` to a fresh `CollectingFacilities`, and clears `withdrawnFacilitators`
   (a withdrawal is scoped to the `(key, view)` it was emitted for).
4. Queues `CheckUpdate(key)` so the new view's facility collection begins immediately.

(`StateTransitions.applyCertifiedTimeoutCertificate`.) If the guarded modify does not fire because
the state already advanced, the apply is a no-op recorded as `not_advanced_race`.

## 5. The TC on the wire: proposal-carried certificates

The view advance is local, but the next leader must prove the rotation to followers.
At view greater than 0 the leader embeds the justifying certificate in its `Proposal`
declaration:

```scala
case class Proposal(
  hash: Hash, facilitatorsHash: Hash, lastSnapshotHash: Hash, view: Long,
  vcc: Option[ViewChangeCertificate],
  timeoutCertificate: Option[TimeoutCertificate] = None,
  ...
)
```

(`declaration.scala:416-422`.) When the leader holds a TC for the
`(view-1, view)` transition it embeds the TC and suppresses the VCC for that proposal
(`GlobalSnapshotConsensusStateAdvancer.scala:1221-1229`), so a proposal carries at most
one view certificate. If the carried TC names a highest QC, the leader must propose that
hash or abort and let the next view retry (`tcMismatch`,
`GlobalSnapshotConsensusStateAdvancer.scala:1231-1262`).

Followers re-validate the proposal-carried TC in `ProposalVccValidator`
(`state/ProposalVccValidator.scala:178-236`). A view greater than 0 proposal must carry
exactly one of `vcc` / `timeoutCertificate`: neither (outside the solo-committee and
round-start-view exceptions) is rejected `view{N}_proposal_missing_view_cert`, and both
is rejected `view{N}_proposal_multiple_view_certs`
(`state/ProposalVccValidator.scala:106-114`). The TC branch checks:

- `tc.toView == proposalView` and `tc.fromView == proposalView - 1`
  (`tc_view_mismatch`),
- quorum met over the frozen committee (`tc_under_quorum`; `certQuorumMet`),
- `tc.facilitatorsHash == facilitatorsHash` (`tc_facilitators_mismatch`),
- `tc.lastSnapshotHash == lastSnapshotHash` plus the per-vote last-snapshot agreement
  (`tc_last_snapshot_mismatch` / `tc_vote_last_snapshot_mismatch`),
- every voter is a member of the frozen committee (`certQuorumMet` requires the signer
  set to be a subset of it),
- the highest carried QC does not diverge and, if present, matches the proposal hash
  (`tc_divergent_highest_qc` / `tc_highest_qc_carry_forward_violation`).

The voters of an accepted proposal-carried TC are recorded as
`state.acceptedTimeoutCertificateVoters`, certified as `ProposalValue.timeoutVoters`, and
carried into the outcome's `lastTimeoutCertificateVoters`. They are record-only: there is
no admission scoring, and which timeout voters a leader reports has no effect on who
votes, who leads or the quorum (ADR-0036; pinned by `GlobalCertifiedLineageReplaySuite`).

## 6. Quorum is never lowered

TC assembly and apply both use the one committee quorum over the frozen round
committee. The former v33 quorum-denominator shrink, which could relax the TC quorum and
shrink the round-local active set to the TC voters, is deleted
([ADR-0036](../adr/0036-single-frozen-voting-set.md); retired doc
[quorum-shrink.md](quorum-shrink.md)). If more than `f` members are silent, no TC forms
and the round halts.

## 7. How it relates to abandonment and recovery

The TC and VCC paths are the *liveness-preserving* response to a stall: they rotate the
leader and keep the round alive at a new view. They do not evict facilitators from the
committed committee (committee changes go through the certified successor rule; see
[README section 9](README.md#9-facilitator-selection)). If neither certificate ever
assembles - too few responsive committee members to reach quorum - the round makes no progress and `StallDetector` eventually hands off to
`AbandonmentTracker`, which abandons the round and, after enough consecutive
non-retriable abandonments, triggers a recovery download. See
[README section 11](README.md#11-stall-detection--eviction) and
[section 13](README.md#13-recovery-pipeline).

## Key files

| Concern | File:line |
|---------|-----------|
| Timeout vote emit interface | `engine/TimeoutVoter.scala:7` |
| Live emitter (sign + spreadDirect) | `engine/GossipingTimeoutVoter.scala:34` |
| Vote / certificate / reason shapes | `consensus/declaration.scala:166-254` |
| TC field on `Proposal` | `consensus/declaration.scala:416-422` |
| Both-track trigger | `engine/ViewChangeManager.scala:80-107` |
| Certificate builder | `engine/TimeoutCertificateBuilder.scala:19` |
| Inbound vote handling | `state/RumorHandler.scala:200-203` |
| FSM dispatch | `state/ConsensusFSM.scala:89-90` |
| Assembly | `state/StateTransitions.scala` (`checkTimeoutCertificateAssembly`) |
| Apply (view advance) | `state/StateTransitions.scala` (`checkTimeoutCertificateApply`, `applyCertifiedTimeoutCertificate`) |
| Follower re-validation of proposal-carried TC | `state/ProposalVccValidator.scala:178-236` |
| Leader embeds TC / QC carry-forward | `dag-l0/.../GlobalSnapshotConsensusStateAdvancer.scala:1221-1262` |
