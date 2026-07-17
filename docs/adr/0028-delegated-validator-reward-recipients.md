# 28. Delegated validator rewards follow committee membership

Date: 2026-07-16

## Status

Accepted

## Context

Classic rewards historically split validator rewards across
`lastArtifact.proofs`, so signature collection and reward eligibility were the same
set. Delegated rewards introduced a deterministic static validator pool and the
tiered consensus introduced a wider signing committee than the Core liveness quorum.
Documentation continued to mix the classic signer rule with the delegated rule.

An evidence-score filter later excluded active peers below the admission promote
threshold from delegated rewards. On IntegrationNet this made a nine-member committee
produce eight validator payouts when one seated peer had score 95. The result looked
signer-based and contradicted the policy that every Core and Tier-1 member earns.

## Decision

Delegated validator rewards follow the frozen round-start signing committee, not
`Signed.proofs` and not an evidence-score subset. The current signing committee is
Core + Tier 1; both tiers split the static validator pool equally. Witness remains
observation-only and non-earning.

Activate this correction with the per-environment
`fields-added-ordinals.delegated-rewards-full-committee` ordinal. Below it, retain the
deployed evidence-score rule strictly for historical replay. At and after it, use the
full committee rule. The activation ordinal is the first snapshot produced by the
corrected jar.

Admission score remains an input to active admission and committee/tier derivation.
Once a peer is seated in Core or Tier 1, it is reward-eligible for that round. Reward
eligibility is therefore decoupled from both the Core liveness quorum and the exact
set of signatures collected before finalization.

Classic rewards retain their historical proof-based recipient rule. The active path
is selected by the Tessellation 3 ordinal and delegated emission epoch gates described
in [the rewards mechanism reference](../consensus/rewards.md).

## Consequences

- A late or missing current-round signature does not remove a delegated reward that
  was earned through round membership; participation evidence affects later seats.
- Delegated reward derivation is deterministic and independent of node-local proof
  accretion.
- Probationary Tier-1 seats earn immediately once admitted. Preventing idle pay must
  happen in admission/tier policy, not through a hidden payout filter.
- Public-network activation requires choosing the correction ordinal before the jar
  produces it; the checked-in `9,999,999` values are fail-closed placeholders.
- Signature grace remains useful for evidence quality and artifact proofs, but it is
  not a delegated reward-fairness mechanism.

This ADR clarifies and amends the reward wording in ADR-0018 and ADR-0019.
