# Discord Announcement Draft - Testnet v4.0.0

> Copy the section below for posting to `#announcements`

---

## Tessellation v4.0.0 - Testnet Release

**Environment:** Testnet
**Version:** v4.0.0-rc.2+

### What's Changing

This release introduces **Merkle Patricia Trie (MPT) state proofs** for global snapshots, replacing the legacy 16-field hash format with a single MPT root hash. This is the foundation for efficient state verification and future light client support.

**State proof transition ordinal: 3,070,000** - At this ordinal, testnet switches from the legacy state proof format to the new MPT-based format.

### Other Highlights

- **Consensus improvements** - FSM-based architecture, randomized facilitator selection, improved fork detection
- **Delegated staking** - Incremental stake updates (activates at ordinal 3,070,000)
- **ClickHouse logging** - Structured log support for centralized monitoring
- **Bug fixes** - CPU starvation, state proof validation, timed triggers, metrics endpoint content type

### Breaking Changes

- **Java 21 required** (up from Java 11)
- **Scala 2.13.18** (up from 2.13.10)
- **Global snapshot format change** at ordinal 3,070,000 (MPT state proofs)

### For Metagraph Operators

Metagraph operators must update to remain compatible. Key changes:
- Update Tessellation dependency to v4.0.0-rc.2
- Update to Java 21 runtime
- Add OSGI-INF merge strategy to `build.sbt`
- Replace deprecated `new URL()` calls with `URI.create().toURL`

No metagraph code changes are needed for MPT - it's handled at the global L0 layer. Metagraph snapshots continue using the existing proof format.

Full upgrade guide: https://github.com/Constellation-Labs/tessellation/blob/develop/docs/release/metagraph-upgrade-guide.md

### Release

https://github.com/Constellation-Labs/tessellation/releases

---

> **Note:** Per release policy, testnet releases do not require advance notice. IntegrationNet and MainNet releases will follow with 3-day and 7-day advance announcements respectively.
