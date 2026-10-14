# Delegated rewards era

This implementation introduces a fixed annual rate of 2% on outstanding native DAG,
recalculated from the previous finalized Global snapshot on each time-triggered reward
cycle. Mainnet inherits PR #1632's provisional activation epoch **2990000** and
program configuration. The epoch is an enabled placeholder, not a deployment-ready
configuration: final reward membership is still empty and must be supplied before
release. Other environments remain disabled unless explicitly configured.

## Economics

The epoch budget in datum is `floor(currentSupply * 2 / (100 * epochsPerYear))`.
The default cadence is 485502 epochs per year. This is a nominal annual rate;
refreshing supply compounds to approximately 2.0201% per year absent other supply
changes. Event-triggered snapshots mint no recurring emission budget.

| Allocation | Percent | Recipient |
| --- | ---: | --- |
| Protocol | 30 | DAG86Joz5S7hkL8N9yqTuVs5vo1bzQLwF3MUTUMX |
| Stardust Treasury | 10 | DAG7BfLURyapaaLoJJtR1LndV1s7NnagS8eqZnQ8 |
| Direct validators | 10 | Previous snapshot signers in the configured reward membership |
| Gross delegation | 35 | All eligible active delegated stakes, including stakes on excluded/offline operators |
| AI Inference | 15 | DAG6CLeP4qepXq36xtzYEdKdn5NB1eDWF6ViJmH8 |

Direct validator rewards are split equally. Ten percent of the gross delegation
pool is reserved as commission and distributed in proportion to delegated stake
among registered, positive-stake, eligible signers. The remaining delegation pool
accrues to delegators by principal plus accrued rewards. An operator's submitted
commission does not affect either calculation in the new era.

Pool and recipient apportionment uses integer largest remainders with canonical
key ordering for ties. Commission is `floor(grossDelegation / 10)`; the delegator
pool receives the remainder. A pool without eligible recipients remains unminted;
there is no carry-forward balance or automatic treasury redirection. The nominal
annual rate and unminted empty-pool policy have been confirmed. Explicit one-time
rewards remain separately supported and are additional issuance.

Existing principal and accrued rewards persist. Modified or newly withdrawing
stakes do not receive reward accrual that the lifecycle transition would discard.
Multiple expired withdrawals to one address are summed. Immediate payments across
program, operator and withdrawal categories are combined by destination; withdrawal
payments release previously accrued rewards and are not counted as fresh issuance.
Overflow or inconsistent accrual fails the new-era calculation rather than losing
payments silently.

## Current supply

`CurrentDagSupply.calculate` sums native wallet balances, outstanding native token
locks, native allow-spend escrow, active delegation rewards, and pending withdrawal
rewards using checked integer arithmetic. Delegated/collateral principal is counted
through token locks only. Metagraph currencies and mirrored lock-balance projections
are excluded.

This is outstanding ledger DAG, not market-reported circulating supply. The calculator
does not infer inaccessible wallets, subtract historical overissuance, or repair stale
records. Before activation, reconcile a canonical snapshot against independently
accounted supply, including processed withdrawals and settled/expired escrow. Verify
the relevant historical cleanup gates. Benchmark a full state scan at Mainnet scale.
No external API or new persisted supply counter is used.

## Registration and APIs

After activation, `POST /node-params` requires at least 1000000 DAG in the signing
source's balance in the previous finalized context. Missing balances count as zero.
Registration seedlist membership and the old 5–10% request range no longer determine
acceptance. The required commission field must still decode within 0–100000000;
signature, source ownership, metadata and parent-reference checks remain.

HTTP admission uses the finalized head's epoch; consensus rechecks at the actual
artifact epoch. New-delegation membership uses the head's next ordinal at HTTP
admission and the artifact ordinal at consensus acceptance. Existing registrations
are not rewritten or required to resubmit.
`GET /node-params/{peerId}` returns the original signed value and reference, while
the unsigned node listing projects effective `rewardFraction = 10000000` after
activation. A staking UI should show “10% (fixed by the network)” and remove commission
editing; no frontend is included in this repository change.

The rewards-information API uses the new supply calculator and cadence after
activation and reconstructs gross delegation rewards using effective 10% commission.
That reconstructed gross amount is an estimate because integer rounding is not
invertible. The existing per-DAG annualized estimate still rounds the per-epoch
ratio early; this change does not establish an accurate net APR display.

The existing native DAG node collateral requirement rises from 250000 to 1000000 DAG
at the same activation boundary. Global snapshot acceptance selects the requirement
from the artifact epoch, preserving historical block validation. DAG L0/L1 live
admission and facilitator selection use the finalized Global head's epoch. A next
snapshot may be event-triggered and retain its epoch, so live checks do not predict
an epoch increment. Consequently, live peer/facilitator checks tighten once the first
head at epoch A is stored; block acceptance and registration enforce the new rules
on the artifact reaching A itself. Fund every participating DAG L0/L1 node-key wallet
before epoch A and verify the remaining facilitator set can sustain consensus.
Currency nodes retain their own collateral policy. This is a spendable node-key
wallet balance check, not a new locked-collateral requirement.

Customer delegation creation has no equivalent numeric minimum, so none is introduced.
The generic token-lock minimum remains unchanged. After activation, new delegations
are restricted to the configured reward membership,
including its scheduled replacements. Before activation the legacy seedlist rule
remains. An empty membership rejects new delegations. Existing stakes on excluded
operators continue accruing, and withdrawals do not require operator membership.
Balance-based operator registration does not itself grant network admission or
permission to receive new delegations.

## Activation and historical compatibility

The new era starts at `epochProgress >= activationEpoch`. `EpochProgress.MaxValue`
is explicitly disabled even at that epoch. Mainnet selects the inherited provisional
`mainnetMetanomicsV2Epoch = 2990000`; a missing environment mapping resolves to the
disabled policy, and genesis activation requires explicit zero. Production callers
all use the no-argument default provider: change `mainnetMetanomicsV2Epoch` there
once to move or disable production activation consistently. Its parameterized
`getConfig(mainnetActivationEpoch)` builder selects the era, emission schedule,
and distribution schedule together for explicit custom wiring or tests. Overriding
one production caller or copying only `eras` is not a supported activation override.

`rewardValidators` is an ordinal-keyed replacement schedule: retain old entries and add strictly future revisions for quarterly
membership updates. Do not infer reward roles from seedlist position. The supplied
provisional membership list has not been installed.

The legacy reward branch and registration rules remain below activation. Snapshot
acceptance and replay use artifact epochs; membership selection separately uses artifact
ordinals. API display uses the epoch of the snapshot being shown.
Signed request types, codecs, hash construction and snapshot field shapes are unchanged.
Economics and registration are replay/state-transition changes. Effective commission
and supply API semantics are deliberate consumer-visible changes requiring review;
do not describe the overall release as runtime-only or assume decoder compatibility
proves consumer compatibility. Re-derivers must supply the era policy explicitly to
the acceptance manager and registration validator; registration and reward-distributor
calls require artifact context explicitly (epoch for activation, ordinal for
membership). This deliberate source API change makes missing consumer wiring fail
compilation instead of silently selecting legacy behavior.

This release branch does not have develop's deterministic-config fingerprint mechanism.
Activation/membership configuration is currently compiled into the default provider,
with Mainnet provisionally enabled at epoch 2990000 and other mappings disabled.
Record exact configuration, code and artifact digests with the release. Preserve
historical authorization separately when the actual network seedlist is replaced; this implementation does not perform that replacement.

Before release, replace or confirm the placeholder, verify it is strictly greater
than the canonical Mainnet head epoch at deployment, and verify it follows the
existing Tessellation 3 migration and delegated-emissions epoch boundary. Install
the intended Top30 reward membership with an ordinal entry effective no later than
the first active artifact. Scan canonical active stakes for
duplicate delegator/node pairs; the new calculation rejects ambiguous records rather
than minting against them. An empty membership intentionally leaves operator pools
unminted, rejects new delegations, and must not be mistaken for a configured Top30 list.

Before deployment, qualify canonical-state supply, boundary/replay state proofs,
Snapshot Streaming, Block Explorer, SDK clients and active metagraph readers. Coordinate
one advertised version and a full-fleet cold restart. After new-era artifacts exist,
do not assume the old binary can resume validation or move the activation threshold
forward over already finalized history.
