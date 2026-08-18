# ADR-0030: Broad Tier-1 signing leases and canonical open admission

Date: 2026-08-04

Updated: 2026-08-10

Status: Accepted

## Context

The first v4.1 admission controller used its score/recent-signer `active` result as
the entire next signing committee. IntegrationNet then lost roughly two seats per
round while admitting at most one. The canonical base for round N+1 is round N's
committee, so this became a shrink ratchet: a 96-peer Ready population produced
only 4-6 snapshot signers and concentrated validator rewards in the same small set.

The admission-vote monitor also reapplied the one-target budget after removing
targets already voted by self. Every poll therefore advanced to another candidate.
Votes split across a PeerId-ordered tail, producing high gossip volume and almost
no certificates.

The intended tier model is different:

- Core is the reliable leader and liveness-certificate cohort.
- Tier 1 is the broad healthy signing and validator-reward cohort.
- Witness is observation/certificate support and is not normally seated.
- Outside bootstrap, the frozen Core + Tier-1 supermajority remains the snapshot
  finality safety floor. No individual Tier-1 peer and no unanimity is required.

The broad signing-lease decision in this ADR is scoped to Global L0, where the
configured phase/finality fraction is two thirds and validator rewards are at
issue. Currency L0 remains on its bounded active-set policy because its configured
threshold is unanimity; broad retention there would make one silent Tier-1 member
blocking. Currency shares only the wire-compatible nomination and gossip-budget
mechanics, with its existing controller-target gate preserved.

## Decision

1. Treat canonical parent membership as a signing lease. A selected peer outside
   the admission controller's Core-eligible result remains seated as Tier 1.
2. End that lease only through existing explicit eligibility/removal rules:
   collateral, withdrawal, penalty/probation, or certified eviction. The existing
   chronic-miss signal is derived from the leader's early Facility responder set,
   not snapshot proofs. It can demote Core/leader eligibility but cannot directly
   delete a Tier-1 signing/reward seat.
3. Keep `active-facilitator-target` and `active-facilitator-max` as bounds for the
   Core controller classification. They are not signing-committee or reward caps.
   The deprecated rendezvous `max-facilitator-count` ceiling is not repurposed.
4. Admit new signing leases only through accepted AdmissionCertificates. In round
   N the leader rendezvous-ranks locally advertised candidates and carries one
   nominee in its signed Proposal. In round N+1, Core voters either vote for the
   parent nominee they retained or abstain; they never rank their differing local
   candidate universes. The Core-quorum certificate—not ephemeral recovered
   nominee state—is the state-transition authority, so a restarted node can accept
   the same certified admission. Rendezvous ranking is deterministic for a parent
   and changes as parent entropy changes; it is neither ascending PeerId order nor
   node-local randomness, and it does not promise that consecutive parents select
   different peers.
5. Enforce the configured open-vote budget across the whole round. Apply the fixed
   target prefix before removing targets already voted by self, so monitor ticks
   cannot walk the candidate tail.
6. Use Core as the voter pool for open expansion. Preserve the wider deterministic
   witness pool for penalty/probation readmission, where it is a recovery path.
   A consensus-agreed probation target may present its quorum certificate while the
   cert-anchored `penaltyUntil` horizon is still carried: accepting that certificate
   is the existing operation that clears the horizon. Active penalties continue to
   reject new/open admission targets.
   Open Ready-at-tip votes and certificates are eligible only on the existing
   `activeAdmissionExpansionIntervalRounds` cadence. The shipped interval is five
   rounds. Before a Global L0 Core member emits an open vote, the fixed nominee must
   return authenticated metadata naming the exact parent ordinal and hash on a fresh
   direct probe and must already have sent an authenticated Facility bound to that voter's
   current round. This proves that the nominee's snapshot view and consensus FSM have
   entered the same round key; it does not promise a future timely signature. `Ready`
   plus a nearby cached tip is not sufficient. A missing Facility, stale/conflicting
   response, or timeout causes abstention and never advances to a second candidate.
   Probation readmission is not cadence-gated. A `readmissionCountdown` map
   entry remains probation authority when its value reaches zero; only an accepted
   AdmissionCertificate removes the entry. When probation and open certificates
   compete for the one-certificate proposal budget, probation recovery is selected
   first and rendezvous ordering resolves peers within the same lane.
7. Rendezvous-rank certificates only when constructing a proposal. Keep the legacy
   `AdmissionCertificate.ordering` at the apply-site defense-in-depth boundary.
8. Do not change reward arithmetic or activation ordinals. Before
   `delegated-rewards-full-committee`, replay uses the legacy recipient filter. At
   and after it, every frozen Core + Tier-1 member is a validator recipient;
   Witness is not.
9. Bump `consensusSchemaVersion` to 34 because Proposal gains the optional
   admission-nominee field. The accepted singleton is carried forward in the
   existing `Finished.candidates` field. The version enters `deterministicConfigHash`.
10. Reuse the existing eviction-certificate path as a bounded bridge for actual
    Tier-1 finality participation. At round creation, update node-local consecutive
    proof-miss streaks for every peer in `currentTier1 intersect
    parentRoundCommittee`, then select one audit target from that complete,
    consensus-agreed set by rendezvous rank. A current Core member emits the existing
    `EvictionVote(Silent)` only when all of the following hold:
    - the target is absent from three consecutive locally finalized parent artifact
      proof sets, reusing `TierTransitions.DemotionConsecutiveMisses`;
    - those observations span at least `(DemotionConsecutiveMisses - 1) *
      timeTriggerInterval`, preserving the normal elapsed observation window when
      EventTrigger accelerates rounds;
    - locally observed current-committee signers are below the finality floor for the
      **current** committee; and
    - the round is on the existing `activeAdmissionExpansionIntervalRounds` cadence.

    Any observed proof resets both the count and elapsed-time streak. Newly admitted
    peers are not auditable until they have had a parent-round signing opportunity.
    Restart, missing parent evidence, or a non-consecutive parent ordinal clears local
    streaks, delaying eviction conservatively. The count, monotonic time, and cadence
    are local vote-emission policy only; certified membership remains authoritative.
    This cadence applies only to the proactive Tier-1 finality audit. Certified Core
    stall eviction remains an every-round liveness recovery path.
11. A Tier-1 target requires a Core-quorum certificate. A Core-target stall eviction
    retains the wider deterministic historical witness pool; the target's frozen tier
    selects between these two existing lanes at both assembly and validation.
12. Treat local proof absence only as an emission observation. Proof subsets may differ
    across honest finalizers and therefore never enter deterministic state directly.
    The existing tip-bound, committee-bound, quorum-signed EvictionCertificate remains
    the sole membership authority, is applied at Proposal acceptance, and affects only
    a later round.
13. Do not change `GlobalIncrementalSnapshot`, `GlobalSnapshotStateProof`, state-proof
    construction/validation, reward arithmetic, or metagraph snapshot processing. The
    finality audit adds no message variant, Proposal field, config value, activation
    ordinal, or schema-version increment beyond the v34 admission work already in
    this ADR.
14. Before a Global-L0 Core node emits an open-admission vote, require the exact
    next-seat headroom invariant:

    ```text
    observed current-committee parent signers >=
      finality floor(current committee size + 1)
    ```

    Count only actual locally finalized parent proofs whose PeerIds are in the current
    committee. Because honest finalizers can hold different valid proof subsets, this
    gate controls only local vote emission. It is not proposal validation, certificate
    validation, committee derivation, or signed state. A certificate forms only when a
    Core quorum independently observes enough headroom. Apply the invariant only outside
    bootstrap, when the full-committee finality floor is active. During bootstrap the
    legacy Core-only finality gate remains active, so admitting a Tier-1 seat does not
    raise the active finality requirement; applying the later floor would also make
    singleton growth impossible under unanimity. Currency L0 does not apply this gate
    because its unanimity policy could never prove an unseated `(n + 1)`th signer.
    Use the related current-seat invariant to create an exact three-state membership
    policy, where `S` is the locally observed current-committee signer count and `F(n)`
    is the configured finality floor for committee size `n`:

    ```text
    expand: S >= F(n + 1)
    hold:   F(n) <= S < F(n + 1)
    evict:  S < F(n)
    ```

    The neutral hold band is intentional. Making eviction the boolean complement of
    expansion causes deterministic boundary oscillation when `F(n) == F(n + 1)` but
    `F(n + 2)` steps upward.
15. Proposal validation counts distinct voter PeerIds in each AdmissionCertificate,
    not the number of signed vote wrappers. DAG and Currency apply the same rule as
    certificate assembly.
16. Report the actual finality requirement outside bootstrap from the frozen Core +
    Tier-1 committee floor, not the Core strict-majority diagnostic. Expose the
    first-quorum finality margin, the open-admission cadence/headroom decision, the
    Tier-1 eviction headroom decision, and sticky probation entries ready at countdown
    zero.

## Consequences

- After the one-round nomination pipeline is primed, a successful open certificate can
  add at most one phase-aligned healthy signing/reward seat on each five-round admission
  cadence with the shipped budget, and only while the prior proof set already satisfies
  the next committee's finality floor. A nominee must prove both exact parent state and
  current-round Facility participation before Core voters attest it. There is no
  controller target or fixed population cap at which broad committee growth is forced
  to stop.
- Core can remain small and reliable while Tier 1 grows toward the healthy Ready
  population. Tier-1 silence does not enter the normal liveness denominator.
- Open-admission gossip is bounded to approximately one vote per Core member per
  cadence opportunity instead of one vote per monitor tick and candidate.
- Finality-audit gossip is bounded to at most one eviction vote per Core member on the
  same five-round cadence as open expansion. No vote is emitted until that node has
  observed three consecutive proof misses over the configured minimum elapsed window
  for the selected target **and** the current committee is already below its own
  finality floor. The neutral current-floor-to-next-floor band changes no membership,
  preventing adjacent-size oscillation. This is an audit-work budget, not a cap on
  Tier-1 membership or rewards.
- A Tier-1 lease is removed only when enough Core nodes independently failed to
  observe its signature by their parent-round finalization cutoffs and the resulting
  certificate is accepted. The assertion is quorum-observed untimeliness, not proof
  that the peer never signed anywhere.
- Loss of more than one third of the frozen signing committee can still halt
  safely before an eviction can take effect. This ADR does not weaken ADR-0021's
  current-round finality floor; the audit is preventative maintenance and cannot
  recover an already-unfinalizable frozen committee.
- Metagraphs already able to decode and validate rc.3 global snapshots do not need a
  new state-proof implementation for this behavior. Snapshot contents and reward
  recipients can legitimately change as committee membership changes, but the
  published artifact/state-proof schema and calculation are unchanged.
- The existing five-round controller expansion cadence, 1.5-second admission
  grace, public configuration values, and reward gate remain unchanged.
