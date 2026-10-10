# Timeout Certificate (Track 2 view advance)

**Status:** As-shipped reference (release/testnet). Wired and active.

The Timeout Certificate (TC) is a HotStuff-aligned, quorum-certified view-advance
mechanism. It runs alongside the older `ViewChangeCertificate` (VCC) path: a stalled
round emits *both* a `ViewChangeVote` (Track 1, VCC) and a signed `TimeoutVote`
(Track 2, TC), and whichever certificate assembles first deterministically advances
the round's view. A formed TC carries the highest known `ProposalQC` so the next
leader inherits any vote-locked proposal hash, and it is re-validated against the same
frozen-Core quorum / hash invariants both when it assembles locally and when it
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
4. Computes the quorum with `certificateQuorum`: only single-proof votes whose signer
   matches the origin and belongs to the frozen `coreFacilitators` are counted, and the
   required size is `CertifiedConsensus.requiredCoreQuorum(coreSize,
   quorumThresholdFraction)`, the same function used by ProposalQC / CoreCommitQC. See
   [section 6](#6-relationship-to-the-retired-quorum-shrink).
5. Calls `TimeoutCertificateBuilder.build(...)`
   (`engine/TimeoutCertificateBuilder.scala:19`).

`TimeoutCertificateBuilder.build` validates, in order:

- every vote's `facilitatorsHash` matches the expected hash
  (`FacilitatorsHashMismatch`),
- every vote's `lastSnapshotHash` matches (`LastSnapshotHashMismatch`),
- every vote's `reason` matches (`ReasonMismatch`),
- only signers in the voter pool passed by the caller (the frozen Core) are counted;
  deduplicated by signer
  (`bySigner` keeps one vote per signer),
- the number of distinct in-pool signers is at least `quorumSize` (`UnderQuorum`),
- the carried `highestKnownQc`s do not diverge: if two QCs at the same view name
  different proposal hashes, the build fails (`DivergentQcs`).

On success it returns `TimeoutCertificate(fromView, toView, facilitatorsHash,
lastSnapshotHash, reason, votes)` (`declaration.scala:247-254`).

The voter pool passed to the builder is the frozen Core (`certificateQuorum` in
`StateTransitions`); VCC uses the same function. The former wider witness pool
(`WitnessPool.all` unioned with `roundStartFacilitators`) is no longer used for VCC/TC;
it remains only for Core-target eviction and probation-readmission certificates
(`EvictionVoterPool`, `AdmissionVoterPool`).

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
frozen-Core quorum, so an apply never trusts the stored certificate blindly. If the
certificate still validates, the transition:

1. Asks the layer's `HealthDerivedMembershipPolicy.timeoutMembership`. Global L0 uses
   `RetainSigningLeases`, which never shrinks: the active set is canonicalized back to
   the frozen `roundStartFacilitators`, Core is unchanged, and the leader pool is the
   frozen Core (round-start committee only if Core is empty).
2. Selects the new leader deterministically over that pool:
   `facilitatorSelector.selectLeader(leaderPool, state.entropy, toView)`.
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
exactly one of `vcc` / `timeoutCertificate`: neither (outside the solo-Core and
round-start-view exceptions) is rejected `view{N}_proposal_missing_view_cert`, and both
is rejected `view{N}_proposal_multiple_view_certs`
(`state/ProposalVccValidator.scala:106-114`). The TC branch checks:

- `tc.toView == proposalView` and `tc.fromView == proposalView - 1`
  (`tc_view_mismatch`),
- distinct signers are a subset of the frozen Core and meet the Core quorum
  (`tc_under_quorum`; a signer outside the frozen Core also rejects here -- the former
  `tc_voter_not_in_pool` rejection no longer exists),
- `tc.facilitatorsHash == facilitatorsHash` (`tc_facilitators_mismatch`),
- `tc.lastSnapshotHash == lastSnapshotHash` plus the per-vote last-snapshot agreement
  (`tc_last_snapshot_mismatch` / `tc_vote_last_snapshot_mismatch`),
- the highest carried QC does not diverge and, if present, matches the proposal hash
  (`tc_divergent_highest_qc` / `tc_highest_qc_carry_forward_violation`).

The voters of an accepted proposal-carried TC are recorded as
`state.acceptedTimeoutCertificateVoters` and feed forward into the next round's
active-facilitator admission scoring (`GlobalSnapshotConsensusStateAdvancer.scala:509`,
`:542`, `:690`). A peer that should have produced a timeout vote but did not can be
demoted via the `CertifiedTimeoutMissing` admission exclusion.

## 6. Relationship to the retired quorum shrink

Earlier builds computed the TC quorum through the v33 quorum-denominator shrink rung
(`QuorumDenominatorShrink`) and could shrink the round-local active set to the TC voters
on apply. Both were removed with the pre-v35 Global L0 engine (ADR-0035 / #1627; see
[quorum-shrink.md](quorum-shrink.md) and the ADR-0021 amendment). The TC is now a plain
frozen-Core quorum certificate; the ADR-0021 finality floor that governs commit is kept
in `FinalityQuorum`.

## 7. How it relates to abandonment and recovery

The TC and VCC paths are the *liveness-preserving* response to a stall: they rotate the
leader and keep the round alive at a new view. They do not evict facilitators from the
committed committee and, on Global L0, do not shrink the round-local active set either.
If neither certificate ever assembles -- too few responsive frozen-Core peers to reach
quorum -- the round makes no progress and `StallDetector` eventually hands off to
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
| Assembly | `StateTransitions.checkTimeoutCertificateAssembly`, `certificateQuorum` |
| Apply (view advance) | `StateTransitions.checkTimeoutCertificateApply`, `applyCertifiedTimeoutCertificate` |
| Follower re-validation of proposal-carried TC | `state/ProposalVccValidator.scala:178-236` |
| Leader embeds TC / QC carry-forward | `dag-l0/.../GlobalSnapshotConsensusStateAdvancer.scala:1221-1262` |
