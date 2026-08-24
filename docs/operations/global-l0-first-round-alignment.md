# Global L0 first-round alignment after a cold restart

Status: implemented for the v4.1.0-rc.12 IntegrationNet candidate.

This mechanism prevents a large Global L0 rollback committee from starting its
first round in different timeout/view domains while validators finish download
at different times. It is local start orchestration around the existing
consensus protocol. It adds no declaration, snapshot, snapshot-info, state
proof, codec, hash, configuration field, or activation ordinal.

It applies only to an established Global L0 chain after normal `run-rollback`.
True bootstrap and Currency L0 retain their existing behavior. Explicit Global
L0 `CL_GL0_RECOVERY_SEED_COMMITTEE` starts retain their stronger all-member
barrier and take precedence over this normal path.

## The failure it closes

Normal rollback seeds its synthetic parent outcome from the selected
incremental anchor's artifact proof signers. Before rc.12, the rollback lead
counted arbitrary `Ready` peers and started after at most two TimeTrigger
intervals, while each validator independently slept one TimeTrigger interval
after its own download completed. A staggered fleet could therefore create and
vote in different views of the same first key. The legacy `FreezeAfterVote`
bridge then correctly refused unsafe cross-view re-voting, but no view could
collect quorum.

The rc.11 deployment supplied the natural experiment: the same 29-member
anchor committee and jar deadlocked after a staggered rollout, then finalized
normally after a tightly synchronized restart. Rc.12 removes dependence on
that timing luck.

## Normal restart protocol

Let `O` be the exact installed parent outcome at key `K`, and let `C` be its
anchor-derived facilitator set. The required alignment count is:

```text
Q(|C|) = QuorumPolicy.fromFraction(|C|, quorum-threshold-fraction)
```

The rollback lead must itself belong to `C`. This is checked at startup. For an
ordinary restart, verify that condition from the selected anchor's proof list
before stopping the fleet.

The lead then:

1. installs `O` and arms the generation-bound `FirstRoundStartGate(K)`;
2. polls only current-session members of `C` in `Ready` or
   `WaitingForReady`;
3. counts only peers serving an outcome value structurally equal to `O`;
4. waits without an elapsed-time escape until `Q(|C|)` members including
   itself are exact;
5. queues the existing serialized `ReleaseFirstRoundStart` command; and
6. derives the ordinary view-0 state and emits its existing Facility.

Successful normal-path responses are cached only for that peer's current
cluster session. The lead probes uncached members concurrently under the
existing three-second per-peer timeout, so unreachable minority members do not
serialize a large committee's startup. A session change invalidates the cache. Explicit operator
recovery does **not** use this cache: its all-member barrier continues to
observe every named member as exact on the current poll.

Each validator in `C`:

1. installs the same outcome and arms its gate before promotion or a timer can
   start a round;
2. remains `WaitingForReady` and does not run an independent first-round timer;
3. waits for a stored Facility at `K.next` from a current-session member of
   `C`;
4. validates the Facility's existing parent ordinal, parent snapshot hash,
   parent facilitator hash, and deterministic consensus-config hash;
5. samples one current-session matching origin per poll and requires its latest
   typed outcome to remain exactly `O`; and
6. releases through the same serialized, generation-bound command and emits
   its own ordinary Facility.

The Facility is only a timing pulse. It has no new authority and changes no
quorum. A Facility from a noncommittee peer, an invalid lifecycle state, a
different parent/configuration, or a peer serving a mismatched outcome cannot
release the gate. If a matching origin is already beyond `K`, the follower
does not open a stale round; it re-enters normal recovery download. A validated
newer initialization explicitly supersedes the older local gate if the node is
not a member of the newer committee.

A committee member that starts late can miss every `K.next` Facility after the
rest of the fleet has already finalized. Authenticated declarations observed at
`K.next.next` or later are therefore used only to select committee peers for a
typed latest-outcome check. Such a declaration cannot release the gate. If the
queried peer proves a committed outcome beyond `K`, the late member follows the
same recovery-download path instead of remaining held forever.

Sampling one origin per follower poll is deliberate. Querying every visible
Facility origin from every held member would create an `O(N^2)` HTTP burst at
exactly the point a large fleet is converging. Local sampling affects only
release timing; it cannot change committee membership, consensus bytes, or the
required quorum.

Validators outside `C` retain the existing join/admission behavior. This is
important: holding every Ready peer would prevent noncommittee candidates from
emitting the registration/Facility traffic used by normal admission.

## State-creation safety boundary

The expected first-round committee is threaded through the shared
`ConsensusRoundRunner` and `ConsensusStateCreator`. DAG and Currency creators
still execute their ordinary deterministic committee derivation. Before
`ConsensusStorage` can commit the new state or run its retained Facility
self-store/direct-delivery effect, the generic boundary requires exact set
equality:

```text
derived roundStartFacilitators == expected C
```

A mismatch raises `UnexpectedRoundStartFacilitators`, leaves the gate held,
commits no consensus state, and sends no Facility. The barrier retries the same
generation; it never silently substitutes a locally observed set. This check
also hardens explicit recovery without changing its all-member release rule.

## Composition with emergency recovery

Startup precedence is strict:

```text
CL_GL0_RECOVERY_SEED_COMMITTEE
  -> selected committee, exact all-member barrier

else established normal GL0 rollback
  -> anchor proof-signer committee, Q(N) lead barrier + Facility pulse

else true bootstrap / Currency L0
  -> existing behavior
```

There is no automatic timeout conversion from normal alignment into an
operator-selected committee. If `Q(N)` exact anchor signers do not return, stop
the attempt and explicitly authorize the trusted recovery-seed procedure with
a newly verified canonical anchor. Automatic local shrink under a partition
would turn missing peers into membership authority and is intentionally
forbidden.

See [Global L0 trusted recovery seed committee](global-l0-recovery-seed-committee.md)
for that fallback. The selected recovery cohort still requires every named
member; rc.12 does not weaken it to `Q(N)`.

## Permissioned early-pulse boundary

Any member of `C` with a valid matching Facility may release a follower. This
is deliberate: if the rollback lead establishes the round and then fails after
another member starts, that member's ordinary Facility can still complete the
cascade. In honest rc.12 operation, validators cannot emit that Facility early
because their own gates are held.

A buggy or malicious trusted committee member that bypasses its local gate may
release only the followers that receive its Facility before the lead observes
`Q(N)`. It still cannot lower a phase/finality quorum, forge another origin, or
change `C`; however, its released subset can start view timers early and may
recreate a visible halt. This is an accepted residual under IntegrationNet's
permissioned/allowlisted threat model. The release-origin log identifies the
operator involved. Byzantine hardening remains v35 work.

## Failure behavior

| Condition | Result |
|---|---|
| Fewer than `Q(N)` exact anchor members return | Lead and members remain held; no timeout escape |
| Unrelated peers become Ready | They do not count toward alignment |
| Expected peer serves another outcome | It is reported as mismatch and does not count |
| Rollback lead is absent from anchor proofs | Startup fails before rollback consensus starts |
| Facility pulse is lost while the first round is active | Held members keep polling stored declarations; normal retransmit may supply it |
| Member starts after the first round already finalized | A future declaration selects an authenticated ahead check; a proven newer outcome re-enters recovery |
| Pulse origin is already ahead | Follower enters recovery instead of opening stale `K.next` |
| Derived committee differs from `C` | No state or Facility commits; gate remains held |
| Minority of `C` is absent after `Q(N)` aligns | Quorum starts; absent members download the successor |
| Old release arrives after reinitialization | `(key,generation)` validation rejects it |
| Explicit recovery input is configured | Normal barrier is not selected |
| One node runs `run-rollback` while the existing fleet remains live | Unsupported: it intentionally remains held rather than unilaterally creating a competing lineage |

## Full-fleet restart runbook

1. Select one canonical incremental anchor by ordinal, hash, state proof,
   snapshot content, and snapshot info. Confirm snapshot-streaming follows that
   lineage.
2. **Before stopping the fleet**, read the anchor artifact proofs and verify the
   intended `run-rollback` source PeerId is one of those proof signers. If it is
   not, select another controlled signer or use the trusted recovery-seed path.
3. Confirm `CL_GL0_RECOVERY_SEED_COMMITTEE` is absent for an ordinary restart.
4. Build/tag one immutable, distinctly advertised rc.12 release. The
   `versionHash` gate hashes the advertised version string (or common
   `CL_VERSION_HASH`), not jar bytes and not `deterministicConfigHash`. Confirm
   both join fences agree fleet-wide.
5. Disable automated restart/rollback actions and stop the complete fleet.
6. Start exactly one verified signer as `run-rollback <anchor-hash>`. Start
   every other process with the ordinary `run-validator` role.
   A standalone `run-rollback` invocation against a still-live fleet is not a
   recovery mode. It cannot satisfy the anchor-committee alignment gate and is
   expected to park fail-closed.
7. Watch the lead's expected committee, required, aligned, and deficit gauges.
   A flat tip while `dag_consensus_normal_first_round_alignment_held == 1` is
   intentional synchronization and is a **DO-NOT-RESTART** condition.
8. Require a lead release with reason `aligned_quorum`, follower releases with
   reason `facility_pulse`, one concentrated view-0 Facility domain, and zero
   committee-mismatch increments.
9. Keep automatic restart inhibited until the first successor is accepted and
   the ordinary committee has positive finality margin.
10. If alignment cannot reach quorum, do not retry blind restarts. Stop the
    attempt, inspect the missing/mismatch classification, then choose a viable
    anchor or explicitly invoke the recovery-seed runbook.

## Metrics

The following metrics are node-local orchestration signals. They never enter a
snapshot or consensus decision:

- `dag_consensus_normal_first_round_alignment_held` — `1` while the local
  normal first-round gate is held. Monitoring must inhibit restart while this
  is `1`.
- `dag_consensus_normal_first_round_expected_committee_size` — anchor-derived
  committee size on each held member.
- `dag_consensus_normal_first_round_required_count` — `Q(N)` on each held
  member.
- `dag_consensus_normal_first_round_aligned_count` — exact aligned population
  observed by the lead.
- `dag_consensus_normal_first_round_alignment_deficit` — additional exact
  members needed by the lead.
- `dag_consensus_normal_first_round_alignment_poll_total{outcome}` — bounded
  lead classifications: `aligned`, `invalid_committee`, `mismatch`,
  `fetch_failed`, `missing_outcome`, `invalid_state`, `missing_session`, or
  `below_quorum`.
- `dag_consensus_normal_first_round_pulse_total{outcome}` — bounded follower
  classifications, including `waiting_for_facility`, `aligned`, and
  `peer_ahead`.
- `dag_consensus_normal_first_round_release_total{role,reason}` — successful
  lead/follower release observations.
- `dag_consensus_normal_first_round_wait_duration_seconds` — local held duration
  histogram as exposed by the metrics backend.
- `dag_consensus_normal_first_round_alignment_error_total{stage}` — contained
  polling/reporting failures; the loop continues without a timeout escape.
- `dag_consensus_normal_first_round_alignment_init_resume_total` — a partially
  installed normal alignment initialization was retried idempotently rather
  than sent back through a new download.
- `dag_consensus_first_round_committee_mismatch_total` — state creator derived
  a set other than the normal or operator-recovery startup expectation. Treat
  any increment as a release blocker requiring anchor/recovery review.
- `dag_consensus_first_round_start_gate_superseded_total{opened}` — a validated
  newer download did or did not supersede a stale older-key hold.

Existing generic gate-held, dropped-trigger, stale-release, and recovery-seed
metrics remain in force. Existing `dag_consensus_committee_core_size` and
`dag_consensus_committee_tier_size` expose the derived Core/Tier-1 composition
when the released state is materialized; the normal expected-committee gauge
is the pre-release full-set signal.

## Compatibility and follow-up

Rc.12 is consensus-behavior changing and must use the normal distinctly
versioned, full-fleet cold restart. It is schema-compatible with rc.11 and does
not require a metagraph activation announcement. Mixed rc.11/rc.12 operation
is not supported.

Forward-port this behavior into the v35/#1566 branch before its eventual
activation. V35's certified outcome and durable recovery work are
complementary; they do not make staggered local first-round timers desirable.
