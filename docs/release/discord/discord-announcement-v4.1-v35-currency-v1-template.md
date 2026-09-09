# v4.1 / v35 and Currency protocol-v1 announcement template

> Draft template only. Copy it to a network- and release-specific file before use. Replace
> every angle-bracket placeholder and preserve the posted copy as release history.

## Advance notice

```text
Network upgrade notice: <NETWORK> will cold-restart on Tessellation <EXACT_RELEASE_TAG> at <DATE/TIME UTC>.

This release carries two separately scheduled signed-behavior boundaries:
- Global L0 consensus schema v35 at Global snapshot ordinal <V35_GLOBAL_ORDINAL>.
- Currency snapshot protocol 1.0.0 at Global snapshot ordinal <CURRENCY_V1_GLOBAL_ORDINAL>.

Metagraph action is required before the Currency boundary. Rebuild and deploy the complete Currency L0, Currency L1, and Data L1 stack against the exact <EXACT_RELEASE_TAG> Tessellation SDK. Active lineages must complete the unappliedGlobalChangeOrdinals preflight. Keep dormant legacy lineages offline until upgraded.

Do not run mixed versions inside a Global L0 or Currency L0 cluster. Release guide: <LINK>. Operator contact: <CONTACT>.
```

## Cold-restart confirmation

```text
<NETWORK> has completed its coordinated cold restart on Tessellation <EXACT_RELEASE_TAG>.

Global v35 remains scheduled for Global ordinal <V35_GLOBAL_ORDINAL>. Currency protocol 1.0.0 remains separately scheduled for Global ordinal <CURRENCY_V1_GLOBAL_ORDINAL>. Snapshot Streaming artifact <SS_VERSION_OR_DIGEST> and the active metagraph census are <STATUS>.

No operator should re-enable a legacy or dormant metagraph producer until its complete stack is rebuilt against the announced SDK.
```

## Global v35 activation confirmation

```text
Global L0 consensus schema v35 activated on <NETWORK> at Global snapshot ordinal <V35_GLOBAL_ORDINAL>.

Observed status: <QC/SIDECAR/FINALITY_SUMMARY>. The separately scheduled Currency protocol boundary remains <CURRENCY_V1_GLOBAL_ORDINAL_OR_STATUS>.
```

## Currency protocol-v1 activation confirmation

```text
Currency snapshot protocol 1.0.0 activated on <NETWORK> at Global snapshot ordinal <CURRENCY_V1_GLOBAL_ORDINAL>.

Active upgraded lineages: <COUNT/LINK>. Dormant legacy lineages must remain offline until upgraded and individually cleared for deterministic resurrection. Observed status: <VERSION_TRANSITION/GLOBAL_ACCEPTANCE_SUMMARY>.
```
