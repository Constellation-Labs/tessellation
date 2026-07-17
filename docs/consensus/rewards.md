# Consensus reward recipients

**Status:** Current GL0 policy and replay-gated implementation as of 2026-07-16.

This document separates three concepts that were previously conflated: the reward
algorithm selected for an ordinal, the committee that is eligible for validator
rewards, and the proof signatures attached to a finalized snapshot.

## Recipient policy

| Reward path | Validator recipient set | Does the current proof set select recipients? |
|---|---|---|
| Classic | `lastArtifact.proofs.map(_.id)` | Yes |
| Delegated, at/after `delegated-rewards-full-committee` | Frozen round-start signing committee (currently Core + Tier 1) | No |
| Delegated, below that correction gate | Historical evidence-score-filtered committee (replay only) | No |

On the delegated path, Core and Tier-1 peers receive equal shares of the static
validator pool. A peer does not lose its share because its signature arrived after
finalization or because its evidence score is below the admission promote threshold.
Evidence affects admission, tier assignment, leader eligibility, and future committee
membership; it is not a second reward-eligibility filter.

Witness is an observation-only tier and does not earn. The current tier-transition
path does not seat Witness peers in the active round, so the frozen
`roundStartFacilitators` passed by GL0 is the Core + Tier-1 signing committee.

The signature grace window is still important for finality evidence, participation
history, demotion, diagnostics, and downstream consumers of `Signed` artifacts. It
does not decide delegated validator payouts.

## Reward-path gates

Delegated rewards require both of these conditions:

1. The produced snapshot ordinal is at or after
   `fields-added-ordinals.tessellation-3-migration`.
2. Its epoch progress is at or after the environment's delegated emission
   `asOfEpoch`.

Otherwise GL0 uses classic rewards, even if the acceptance manager has already
constructed a `DelegateRewardsInput`.

| Environment | Tessellation 3 ordinal | Delegated emission `asOfEpoch` |
|---|---:|---:|
| Mainnet | 4,409,045 | 2,311,565 |
| Testnet | 2,497,000 | 997,094 |
| IntegrationNet | 3,330,000 | 751,085 |
| Dev | 0 | 0 |

The recipient correction has its own
`fields-added-ordinals.delegated-rewards-full-committee` gate. Below it GL0 replays
the briefly deployed evidence-score filter; at and after it GL0 pays the full frozen
signing committee. Public-network values remain `9,999,999` placeholders until the
first ordinal that will be produced by the corrected jar is chosen.

The similarly named `incremental-delegated-staking-starting-ordinal` is not the
classic-to-delegated reward switch. It gates population of the incremental delegated
stake record fields `currentTokenLockRef` and `currentAmount`, using a strict
`ordinal > gate` comparison. IntegrationNet's value is 5,075,000.

## IntegrationNet diagnosis

On 2026-07-16 the public IntegrationNet Global L0 endpoint returned ordinal
5,845,181 and epoch progress 1,381,668. Both delegated-reward gates were therefore
active. The snapshot contained nine proofs, while its preceding controller-evidence
entry contained nine round-start facilitators and eight completed signers.

The eight validator payouts observed at that point were caused by an evidence-score
filter introduced with the active-admission expansion work. One seated peer had score
95, below `active-admission-promote-threshold = 100`, so the implementation paid eight
committee peers. That was neither the classic reward path nor the intended
all-Core-and-Tier-1 policy. The filter is retained only below the correction ordinal
to reproduce those already-signed snapshots.

## Code path

1. `GlobalSnapshotAcceptanceManager.calculateRewards` selects `ClassicRewardsInput`
   before the Tessellation 3 ordinal and `DelegateRewardsInput` at/after it.
2. `GlobalSnapshotConsensusFunctions.shouldUseDelegatedRewards` applies the ordinal
   and emission-epoch gates together.
3. `GlobalSnapshotConsensusStateAdvancer.createArtifact` supplies the frozen
   `roundStartFacilitators`, not the artifact proofs.
4. `GlobalSnapshotConsensusFunctions` selects legacy replay or full-committee
   recipients at `delegated-rewards-full-committee`.
5. `GlobalDelegatedRewardsDistributor` divides the static validator pool equally
   across that list on `TimeTrigger`. `EventTrigger` processes stake state but emits
   no periodic reward pool.

When diagnosing a payout, compare the produced ordinal and epoch to both gates, then
compare `peerHistory.controllerEvidence[ordinal - 1].roundStartFacilitators` with
`rewards`. Do not use top-level `proofs` as the expected delegated recipient set.

Before deploying the correction, replace the target environment's `9,999,999`
placeholder with the first snapshot ordinal the corrected jar will produce. Missing
that coordination either leaves the bug active or makes historical replay diverge.
