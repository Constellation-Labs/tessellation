# 27. Cross-cutting operating invariants

Date: 2026-06-30

## Status

Accepted

## Context

Several invariants recur as repeated corrections during the v4.1.0 work and underpin deploy, versioning, and config decisions, but they are written down nowhere formal. Capturing them prevents the same mistakes and orients new contributors. (Each is a standing operating rule, not a single mechanism; the per-mechanism decisions live in ADR-0016 through ADR-0026.)

## Decision

Record four standing invariants:

1. **Version and consensus-config hashes are connect-time fences; `FieldsAddedOrdinals` gates replay.** Joining checks the Tessellation version hash, metagraph version hash, environment, and `consensusConfigHash` when both peers provide it. `consensusSchemaVersion` is folded into `deterministicConfigHash`, which is also checked on Facility declarations. `RegistrationRequest.jar` is advertised and stored but is not compared during joining, so operators must verify identical release artifacts outside the protocol. None of these connect-time values gates replayed history -- that is the job of the ordinal gates (`FieldsAddedOrdinals`, ADR-0025). A wrong ordinal is NOT caught at handshake; it forks the chain when the gate is crossed.

2. **All networks deploy via full cold restart** (testnet / integrationnet / mainnet), all-or-nothing. There are no rolling upgrades and no version-skew tolerance. Every schema bump in this line assumes a coordinated cold restart; do not design for in-place or staggered upgrades.

3. **Hypergraph and metagraph scale oppositely.** Global L0 / dag-l0 is the hypergraph: hundreds of nodes, supermajority quorum (`core = 9` is right for gl0). Metagraphs (currency-l0 / state channels) are few-node and run unanimity (`quorum = 1.0`). Per-environment config conditioned on this includes `clusterFloorActive` (ADR-0021) and B2 being inert on currency-l0 (ADR-0022). Do not transplant a hypergraph node-count assumption onto a metagraph or vice versa.

4. **Follow established BFT protocols rather than invent.** Consult HotStuff / Flow Jolteon / Aptos DiemBFT source before implementing a consensus mechanism. This was the explicit basis for ADR-0018, ADR-0020, and the deferred phase-based membership design.

## Consequences

- Stops a class of recurring mistakes and shortens onboarding.
- **Cost:** these invariants must be revisited if the operational model ever changes -- for example,
  if the deploy model gains rolling-upgrade support or joining begins enforcing the advertised jar
  hash. An ADR superseding this one should be written at that point rather than editing in place.
