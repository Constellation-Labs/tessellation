# Mainnet delegated rewards: approved PR #1633 backport

This branch adapts the approved behavior at `c94314484065f51284cc6d0297307d2cd857bc5d`
to `release/mainnet` at `ade55191acea77bd99b023252aa949dbb8b22c2f`.
It supersedes the earlier proposal's Top-30 membership schedule, network collateral
increase and largest-remainder apportionment.

## Activation and issuance

`DefaultDelegatedRewardsConfigProvider.mainnetMetanomicsV2Epoch` remains **2990000**,
the previously accepted placeholder. Mainnet selects the new behavior at epochs
`>=` this value. Other environments retain their existing policies. This document
does not authorize activation or deployment; select the final activation against
the canonical finalized head before release.

After activation, the nominal annual emission rate is 2%, refreshed from the previous
finalized snapshot for each reward epoch. The configured cadence is 485502 epochs
per year. The supply base includes spendable DAG balances, active global token-lock
principal, native allow-spend escrow, and unpaid active/pending delegation rewards.
It excludes metagraph currencies and mirrored balances. Repeated refresh compounds
the nominal rate; 2% is not a fixed annual yield on individual delegated stakes.

| Allocation | Share |
| --- | ---: |
| Protocol | 30% |
| Stardust Treasury | 10% |
| Validator rewards | 10% |
| Gross delegation rewards | 35% |
| AI Inference | 15% |

Commission is fixed at 10% of the gross delegation pool: 31.5% of total emissions
is net delegation rewards and 3.5% is commission. The separate 3% staking top-up
is disabled. Each payment retains the approved half-up rounding, so total issuance
can differ slightly from the nominal budget. Pools without eligible recipients
remain unminted.

Stardust Treasury and AI Inference both use the existing `stardustNewPrimary`
wallet, `DAG8vD8BUhCpTnYXEadQVGhHjgxEZZiafbzwmKKh`. Their weights are
combined into one 25% payment per epoch, rounded once for that destination.
The protocol wallet is unchanged.

## Eligibility and registration

Existing delegations continue earning their net rewards regardless of whether their
registered operator participates. Operator payments retain release/mainnet's existing
previous-snapshot-signer selection. Commission is allocated using participating
operators' stake, redistributing inactive operators' commission among eligible
participants. Develop's newer committee-selection machinery is not backported.

After activation, creating or editing an operator registration requires at least
1,000,000 spendable DAG in the signed registration's source address. Registration
no longer requires seedlist membership. Creating a delegation requires a registered
target operator whose registered source address still holds that balance; the target
need not belong to the consensus seedlist. Signature, parent and existing parameter
validation remain in force. These checks use finalized snapshot pre-state balances.
They do not reserve funds, continuously enforce balances, or introduce a minimum
amount for the delegator. Existing withdrawal rules remain unchanged.

The network seedlist matches PR #1637 at `9799c93c`: 46 existing IDs remain
(3 source nodes, 31 operators, and 12 metagraph nodes), removing 260 IDs. This
network admission change applies when the new seedlist is deployed, independently
of the rewards epoch gate. Existing network collateral requirements are unchanged.
There is no separate reward-membership schedule to populate on this branch.

## API and payout compatibility

Raw signed registrations and their parent/reference hashes are unchanged. Unsigned
operator listings and gross reward estimates report the effective 10% commission
after activation. Submitted commission values retain existing validation bounds.
The release branch's inline rewards-info calculation and response shape are retained;
the newer develop calculator refactor and MPT validator are not imported.

Operator static rewards and commission are combined per destination, then withdrawal,
operator and reserved payments are combined again at the final credit boundary.
Checked arithmetic prevents overflowing payouts or recipient balances from being
silently dropped. The release implementation supplies this checked credit helper
because it lacks develop's extracted RewardAcceptanceManager.

This is an epoch-gated replay/state-transition change with unchanged signed snapshot
and registration encodings. New registration/delegation rejection cases extend the
HTTP validation-error vocabulary, as in the approved PR; clients must tolerate them.
Below activation the historical reward, seedlist validation and unchecked credit
paths remain unchanged. Producer and replay select the artifact epoch. Existing
release withdrawal accounting and token-lock lifecycle are preserved rather than
importing unrelated develop fixes or newer record fields.

## Validation and release work

Focused tests cover real Mainnet configuration, activation boundaries, refreshed
supply, distribution/rounding, offline commission redistribution, no-signers behavior,
registration/delegation balance checks, effective API display and overflow handling.
A release-specific integration test runs actual production acceptance and historical
recreation at A-1, A and A+1, plus EventTrigger, including a withdrawal equal to an
operator payout, principal release, accrued rewards and matching state proofs/hashes.

Canonical supply reconciliation, activation selection, full historical/fleet restart
qualification, and external consumer compatibility remain release qualification work.
The placeholder and passing unit tests do not establish that those steps are complete.
