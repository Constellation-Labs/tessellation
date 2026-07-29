# Discord Announcements - IntegrationNet v4.1.0-rc.3

**Status:** Draft; awaiting review

Each fenced block is a standalone Discord message and must remain below Discord's 2,000-character
message limit.

## Advance notice - post 2026-07-29

```text
Tessellation v4.1.0-rc.3: IntegrationNet network update

Environment: IntegrationNet
Network restart: Monday, August 3, 2026
Feature activation: Global Snapshot ordinal 5,880,000, estimated Tuesday, August 4
Hard fork: Yes

IntegrationNet will undergo a coordinated cold restart to v4.1.0-rc.3. The restart prepares six consensus changes that activate together only when the signed Global Snapshot ordinal reaches 5,880,000:

- delegated rewards go to every frozen Core and Tier 1 committee member;
- metagraph data-update fees require a valid signature from the spending token wallet;
- state-channel fee affordability uses the deterministic snapshot context;
- Global Snapshot state proofs include per-field sub-trie roots; and
- allow-spend/token-lock validation and set-sum calculation fixes activate.

No feature gate activates merely because the network restarts.

Action required: IntegrationNet metagraph operators must upgrade every Currency L0 and Currency L1 node to v4.1.0-rc.3 before ordinal 5,880,000. Data-update fees spend the metagraph token and remain separate from DAG snapshot fees paid by the metagraph Owner. No metagraph application API change is included.

Release: https://github.com/Constellation-Labs/tessellation/releases
```

## Restart confirmation - post 2026-08-03

```text
Tessellation v4.1.0-rc.3 is live on IntegrationNet.

The coordinated cold restart is complete. This was a hard fork; IntegrationNet nodes must run the v4.1.0-rc.3 release and matching consensus configuration.

The six announced consensus gates are not active yet. They activate together at signed Global Snapshot ordinal 5,880,000, currently estimated for Tuesday, August 4.

IntegrationNet metagraph operators must have every Currency L0 and Currency L1 node upgraded before that ordinal. The activation includes full Core + Tier 1 delegated rewards, spending-wallet signature verification for metagraph data-update fees, deterministic state-channel fee balance checks, per-field state-proof roots, and the announced validation fixes.

Release: https://github.com/Constellation-Labs/tessellation/releases
```

## Activation confirmation - post when ordinal 5,880,000 finalizes

```text
IntegrationNet v4.1.0-rc.3 feature activation complete

Global Snapshot ordinal 5,880,000 has finalized. All six announced v4.1.0-rc.3 consensus gates are now active on IntegrationNet:

- delegated rewards cover every frozen Core and Tier 1 committee member;
- metagraph data-update fees require a valid signature from the spending token wallet;
- state-channel fee affordability uses the deterministic snapshot context;
- Global Snapshot state proofs include per-field sub-trie roots; and
- allow-spend/token-lock validation and set-sum calculation fixes are active.

For clarity, metagraph data-update fees spend the metagraph token and are not DAG snapshot fees. DAG snapshot fees remain the separate Owner-to-Hypergraph mechanism.

Metagraph operators should confirm that all Currency L0 and Currency L1 nodes report healthy operation on v4.1.0-rc.3.
```
