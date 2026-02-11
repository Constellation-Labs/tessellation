# Discord Announcement Draft - IntegrationNet v4.0.0

> Copy the section below for posting to `#announcements`

---

## Tessellation v4.0.0 - IntegrationNet Release

**Environment:** IntegrationNet  
**Version:** v4.0.0  
**Release Date:** TBD  
**State Proof Transition:** TBD (ordinal will be announced separately)

### What's Changing

This release introduces **Merkle Patricia Trie (MPT) state proofs** for global snapshots, replacing the legacy 16-field hash format with a single MPT root hash. This is the foundation for efficient state verification and future light client support.

### Release Timeline

| Date | Event |
|------|-------|
| **Release Day** | v4.0.0 deployed to IntegrationNet (MPT ordinal set to max, feature inactive) |
| **+1 week** | Expected state proof transition date |
| **Transition - 1 day** | Ordinal number announced |
| **Transition Day** | MPT state proofs activate at announced ordinal |

> This staged approach gives metagraph developers time to update their nodes after the release before the breaking change activates.

### Testnet Status

MPT has been running stable on Testnet since **2026-02-06** with state proof transition at ordinal **3,070,000**.

### Other Highlights

- **Consensus improvements** - FSM-based architecture, randomized facilitator selection, improved fork detection
- **Delegated staking** - Incremental stake updates
- **ClickHouse logging** - Structured log support for centralized monitoring
- **Bug fixes** - CPU starvation, state proof validation, timed triggers, metrics endpoint content type

### Breaking Changes

- **Java 21 required** (up from Java 11)
- **Scala 2.13.18** (up from 2.13.10)
- **Global snapshot format change** at transition ordinal (MPT state proofs)

### For Metagraph Operators

Metagraph operators must update to remain compatible. Key changes:
- Update Tessellation dependency to v4.0.0
- Update to Java 21 runtime
- Add OSGI-INF merge strategy to `build.sbt`
- Replace deprecated `new URL()` calls with `URI.create().toURL`

No metagraph code changes are needed for MPT - it's handled at the global L0 layer. Metagraph snapshots continue using the existing proof format.

Full upgrade guide: https://github.com/Constellation-Labs/tessellation/blob/develop/docs/release/metagraph-upgrade-guide.md

### Release

https://github.com/Constellation-Labs/tessellation/releases

---

> **Note:** Per release policy, IntegrationNet releases require 3-day advance notice. This announcement serves as that notice. MainNet release will follow with 7-day advance announcement after IntegrationNet validation.
