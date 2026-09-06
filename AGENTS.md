# Repository agent instructions

## Consensus and history compatibility

Before changing consensus, snapshot construction/acceptance, hashing, serialization, state proofs,
persistence, P2P messages, or SDK-visible types, read
[`docs/adr/0034-consensus-schema-change-governance.md`](docs/adr/0034-consensus-schema-change-governance.md).

Every affected PR must classify itself as runtime-only, replay/state-transition, or schema/wire.
Do not call a change schema-neutral merely because it does not edit a file under `schema/`.

A coordinated cold restart permits the fleet to adopt new runtime behavior together, including
behavior that produces different future consensus outcomes. It does not make changes to signed
encodings, hash/signature construction, persisted history, replay semantics, Snapshot Streaming,
Block Explorer, public APIs, SDKs, or metagraph artifacts automatically compatible. Schema/wire
changes require an explicit design and rollout decision; replay/state-transition changes require a
historical compatibility boundary when old artifacts would otherwise re-derive differently.

When classification is uncertain, treat the change as schema/wire-impacting until the exact signed
and persisted surfaces and external consumers have been audited. Do not implement or approve an
unplanned schema change as an incidental part of a behavioral fix.
