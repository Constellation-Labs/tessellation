# Delegated-stake settlement activation audit

The `fixing-delegated-stake-double-withdrawal` gate is a replay/state-transition change.
Do not activate a public network until the evidence below is recorded and reviewed.
Synthetic tests establish the algorithm's behavior, not the provenance of live rewards.

## Entitlement policy and its limits

One backed token lock is settled once. Duplicate rewards are never added together.
For overlapping cumulative records of one stake lineage, the largest recorded reward is the
most complete known entitlement. Selection includes all copies retired at that settlement,
including copies with later withdrawal cooldowns. Otherwise cleanup could destroy a larger
entitlement after paying an earlier, smaller one.

This is an explicit policy assumption, not a claim that arbitrary corrupted rewards are valid.
Numerically ordering two amounts does not prove that their accrual histories overlap.
Switching to the minimum does not validate the records either: it can underpay a genuine lineage,
and neither minimum nor maximum reconstructs independent or previously paid entitlements.
Unexplained groups block public activation; do not invent counts or an audited ordinal.

The active signed token lock determines the recipient and principal. The pending bucket and
signed stake source are not authoritative for malformed state. Distinct locks stay distinct,
and their entitlements are combined only with checked `Amount.plus`. An arithmetic failure
aborts settlement; it must not wrap, clamp to zero, silently skip a reward, or clean pending state.
Withdrawal payout realizes previously accrued rewards and is not counted again as new issuance.

## Required evidence

Preserve an immutable source artifact, its Global Snapshot ordinal and hash, retrieval time,
network, and independent canonical-lineage verification. Record:

1. Total pending records, unique effective token locks, duplicate groups and duplicate copies.
2. Each group's original/effective lock references, stake/parent references, accepted ordinals,
   withdrawal epochs, owners, active backing and rewards. On v4.1 use `currentTokenLockRef`
   when present; the original event hash is not necessarily the live backing.
3. Historical create/redelegation/withdrawal/replacement events and accrual history establishing
   whether the group's rewards are cumulative and overlapping. Check for earlier settlements.
4. Ownership mismatches, missing backing, active-stake overlap, staggered cooldowns,
   independent/non-nesting lineages and arithmetic failures. Resolve every unexplained anomaly.
5. Exact proposed payouts, lock removals and retained/retired pending records, compared with the
   validated lineage. Audit arithmetic must use exact integers, not floating-point JSON numbers
   or unchecked fixed-width sums. Record tool version, input digest and reproducible commands.

A snapshot census alone can count duplicates; it cannot establish historical nesting or prove
that no earlier payout occurred. Keep both the census and lineage evidence linked from the PR
and release dossier. This document does not record a completed network audit.

## Branch-specific behavior and qualification

- Mainnet #1592: https://github.com/Constellation-Labs/tessellation/pull/1592
- Develop #1593: https://github.com/Constellation-Labs/tessellation/pull/1593

Both gates cover acceptance, reward/principal settlement, checked withdrawal and current-issuance
totals, fail-on-overflow reward credits, and retirement of settled duplicate pending records.
Natural expiry is a separate balance path: suppress generated principal unlocks when
`unlockEpoch < currentEpoch`, so a lock is not credited by both paths. Equality is not yet natural
expiry. Public mappings remain absent until the audit and coordinated activation are approved.

Mainnet skips missing-lock reward/principal payouts and retires processed orphan copies.
Develop retains missing-lock pending records. In particular, when a replacement rewrites OLD
to NEW at R, NEW is not in the last-active set until R+1: preserve the pending reward, unlock
OLD at R, then settle NEW once at R+1. Do not copy Mainnet orphan deletion into that deferral.
Retire later-cooldown copies only for effective locks that are actually settled.

Qualify A-1/A/A+1, original and effective refs, wrong-source highest-reward records, distinct
locks, deterministic ties, staggered cooldowns, repeated settlement, zero rewards, natural expiry
at both boundaries, exact numeric bounds and positive-wrap overflow inputs. Exercise the real
reward distributor, balance credit, principal transition and cleanup together. For develop,
include nonzero-reward replacement R/R+1 coverage.

The old branch remains available below activation solely to reproduce retained signed history;
historical unchecked arithmetic is not the implementation to use for new settlement. This fix
is not a repository-wide arithmetic audit and does not undo historical balance effects.
Use the normal single-version full-cluster cold restart and a strictly future activation ordinal.
No signed schema, codec or hash construction is changed.
