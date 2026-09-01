# Discord Announcements — IntegrationNet v4.1.0-rc.13

Status: Draft — do not post until the release artifact, Snapshot Streaming artifact, operator contact,
and restart window are confirmed.

Prepared: 2026-09-01

Planned coordinated cold restart: 2026-09-04 20:00 UTC

Global L0 v35 activation ordinal: `5923000`

Currency snapshot protocol `1.0.0` activation ordinal: `5923000`

Security/accounting activation ordinal: `5923000`

The gates are intentionally listed separately even though they use the same Global snapshot
ordinal. At the observed IntegrationNet pace on 2026-09-01, ordinal `5923000` is estimated for
approximately 2026-09-05 19:40 UTC. This timestamp is informational; the ordinal is authoritative.

Before posting, replace every `<...>` field and confirm:

- `v4.1.0-rc.13` is the exact published tag and all node artifacts come from that tag.
- The cold-restart window still provides at least three days of advance notice.
- The Snapshot Streaming version or digest is pinned to its reviewed artifact.
- The active GL0 and metagraph operator rosters are complete.
- All active metagraph stacks have been rebuilt against the exact v4.1 SDK.

## 1. Advance notice

```text
IntegrationNet upgrade notice — Tessellation v4.1.0-rc.13

IntegrationNet is scheduled for a coordinated full-fleet cold restart on Friday, September 4 at 20:00 UTC. This is a hard fork: do not run mixed Tessellation or SDK versions.

At restart, Currency L0 returns to its flat synchronous consensus engine. At Global snapshot ordinal 5,923,000, these separately configured boundaries activate:
• Global L0 v35 certified consensus
• Currency snapshot protocol 1.0.0
• complete data-application fee validation
• replay-safe allow-spend escrow and single-settlement rules

The anti-resurrection ledger first applies to evaluated Global ordinal 5,923,001.

Activation is estimated near Saturday, September 5 at 19:40 UTC. Chain pace can vary; ordinal 5,923,000 is authoritative.

This candidate also adds checked fee arithmetic and exact balance-adjustment authorization. Metagraph operators must rebuild and deploy the complete Currency L0, Currency L1, and Data L1 stack against the exact v4.1.0-rc.13 SDK. Audit `unappliedGlobalChangeOrdinals`; keep dormant or legacy stacks stopped.

Candidate: https://github.com/Constellation-Labs/tessellation/pull/1566
Guide: https://github.com/Constellation-Labs/tessellation/blob/v4.1.0-rc.13/docs/release/metagraph-upgrade-guide.md
Support: <OPERATOR_CONTACT>
```

## 2. Cold-restart confirmation

Post only after every required node and supporting service has been checked.

```text
IntegrationNet v4.1.0-rc.13 restart complete

The coordinated cold restart is complete. The active GL0 fleet is running the exact v4.1.0-rc.13 release with no mixed-version nodes observed. Currency L0 cohorts are running the flat synchronous engine.

Activation boundaries remain:
• Global L0 v35: 5,923,000
• Currency snapshot protocol 1.0.0: 5,923,000
• security/accounting gates: 5,923,000

Snapshot Streaming artifact: <SS_VERSION_OR_DIGEST>
Active GL0 census: <GL0_CENSUS_RESULT>
Active metagraph census: <METAGRAPH_CENSUS_RESULT>

The estimated activation time may move with chain pace. Ordinal 5,923,000 is authoritative. Dormant or legacy metagraph stacks must remain stopped until rebuilt and audited.
```

## 3. Global L0 v35 activation confirmation

Post only after observing certified v35 outcomes and completing the activation checks.

```text
IntegrationNet Global L0 v35 is active

Global L0 v35 certified consensus activated at Global snapshot ordinal 5,923,000.

Observed activation snapshot: <ACTIVATION_SNAPSHOT_HASH>
Certified-outcome health: <QC_HEALTH_RESULT>
Snapshot Streaming reconciliation: <SS_RECONCILIATION_RESULT>
Block Explorer reconciliation: <BE_RECONCILIATION_RESULT>

The active fleet remains on v4.1.0-rc.13. The staged v2 same-prefix recovery mechanism is not included, and its fork-recovery E2E remains disabled. Existing rc.12 runtime recovery behavior is unchanged.
```

## 4. Currency snapshot protocol activation confirmation

Post separately from the Global v35 confirmation after the Currency checks pass.

```text
IntegrationNet Currency snapshot protocol 1.0.0 is active

Currency snapshot protocol 1.0.0 activated at Global snapshot ordinal 5,923,000. Currency L0's flat synchronous consensus engine was enabled by the coordinated v4.1.0-rc.13 restart, not by this ordinal gate.

Active metagraph stacks were rebuilt against the exact v4.1.0-rc.13 SDK, their historical unapplied Global changes were audited, and no legacy or mixed-version stack is participating.

Currency health: <CURRENCY_HEALTH_RESULT>
Metagraph confirmation: <METAGRAPH_CONFIRMATION_RESULT>

Dormant legacy stacks must remain stopped unless fully rebuilt and audited before rejoining.
```

## 5. Security/accounting activation confirmation

```text
IntegrationNet v4.1 accounting boundaries are active

At Global snapshot ordinal 5,923,000, IntegrationNet activated complete data-application fee validation and the replay-safe allow-spend destination-credit and expired-spend single-settlement boundaries. The anti-resurrection ledger first applies to evaluated Global ordinal 5,923,001.

Fee validation health: <FEE_VALIDATION_RESULT>
Allow-spend health: <ALLOW_SPEND_RESULT>
Supply reconciliation: <SUPPLY_RECONCILIATION_RESULT>

No mixed-version Global L0 or metagraph cohort is participating.
```
