# 0029. Fee transaction wallet authorization

Date: 2026-07-27

## Status

Accepted

## Context

Metagraph data applications can charge a fee for a data update. The fee is represented as a
`Signed[FeeTransaction]` and moves the metagraph's currency from an external payer to the
destination selected by the application or client.

This mechanism is distinct from both:

- snapshot fees, which are paid in DAG by a metagraph Owner to the Hypergraph; and
- `SpendTransaction`, which is an inclusion artifact used by a metagraph to spend from its own
  currency address.

Historically, consensus checked that a fee proof identified the source wallet, but did not verify
that the proof's signature covered the exact serialized `FeeTransaction`. A proof could therefore
be reused with changed transaction fields.

## Decision

At the `fee-transaction-security` global snapshot ordinal:

1. Every fee proof is verified against the hash of `FeeTransaction.serialize(transaction)`.
2. At least one valid proof must belong to the source wallet.
3. Every additional proof must be valid and signer identities must be unique.
4. A fee transaction can contain at most 16 proofs. The limit is checked before cryptographic
   verification.
5. L1 consensus and submission use the latest Global Snapshot ordinal.
6. Metagraph L0 consensus and final snapshot acceptance use the parent Currency Snapshot's signed
   `globalSyncView.ordinal`. Currency Snapshot ordinals do not activate this protocol rule.

The application retains its existing fee-recipient policy. This change does not use the snapshot
fee Owner wallet as the data-update fee recipient and does not change the metagraph framework API.
In particular, the ordinal passed to an ML0 application's `validateFee` callback remains the
Currency Snapshot ordinal; only the platform security gate uses the parent Global Snapshot ordinal.

Development activates the rule from ordinal `0`. IntegrationNet activates at Global Snapshot
ordinal `5880000`; Mainnet and Testnet retain the disabled `9999999` placeholder until their
coordinated activation ordinals are selected. If the current environment is absent from the
configured map, validation remains on the historical path at `SnapshotOrdinal.MaxValue` to prevent
accidental retroactive activation during replay.

## Consequences

- Currency L1 and Currency L0 operators must upgrade before the configured activation ordinal.
- Existing fee transactions remain valid when the source wallet's exact signature is present.
- Valid co-signers are supported, but they cannot authorize a transaction without the source
  wallet.
- Duplicate/orphan fee mapping, replay policy, aggregate bundle debits, and configurable split
  recipients remain separate changes.
