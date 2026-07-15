# 27. Cross-cutting operating invariants

Date: 2026-06-30

## Status

Accepted

## Context

Several invariants recur as repeated corrections during the v4.1.0 work and underpin deploy, versioning, and config decisions, but they are written down nowhere formal. Capturing them prevents the same mistakes and orients new contributors. (Each is a standing operating rule, not a single mechanism; the per-mechanism decisions live in ADR-0016 through ADR-0026.)

## Decision

Record four standing invariants:

1. **The jar hash is the primary binary fence; `consensusSchemaVersion` is a secondary connect-time fence; neither gates replay.** Peer-connection-time refusal keys on the jar hash -- for a code change the jar hash already fences the deploy. `consensusSchemaVersion` (a single integer wire-version anchor) is folded INTO `deterministicConfigHash`, the config fence checked at handshake and on the Facility `consensusConfigHash`, so a divergent value also rejects the peer connection. But neither is signed into the snapshot artifact, so neither gates replayed history -- that is the job of the ordinal gates (`FieldsAddedOrdinals`, ADR-0025). A wrong ordinal is NOT caught at handshake; it forks the chain when the gate is crossed.

2. **All networks deploy via full cold restart** (testnet / integrationnet / mainnet), all-or-nothing. There are no rolling upgrades and no version-skew tolerance. Every schema bump in this line assumes a coordinated cold restart; do not design for in-place or staggered upgrades.

3. **Hypergraph and metagraph scale oppositely.** Global L0 / dag-l0 is the hypergraph: hundreds of nodes, supermajority quorum (`core = 9` is right for gl0). Metagraphs (currency-l0 / state channels) are few-node and run unanimity (`quorum = 1.0`). Per-environment config conditioned on this includes `clusterFloorActive` (ADR-0021) and B2 being inert on currency-l0 (ADR-0022). Do not transplant a hypergraph node-count assumption onto a metagraph or vice versa.

4. **Follow established BFT protocols rather than invent.** Consult HotStuff / Flow Jolteon / Aptos DiemBFT source before implementing a consensus mechanism. This was the explicit basis for ADR-0018, ADR-0020, and the deferred phase-based membership design.

## Consequences

- Stops a class of recurring mistakes and shortens onboarding.
- **Cost:** these invariants must be revisited if the operational model ever changes -- e.g. if the deploy model gains rolling-upgrade support, or if the connection-time version gate moves off the jar hash. An ADR superseding this one should be written at that point rather than editing in place.
