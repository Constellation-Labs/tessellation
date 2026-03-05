# Discord Announcement Draft - MainNet v4.0.0

> Copy the section below for posting to `#announcements`

---

## Tessellation v4.0.0 - MainNet Release

**Version:** v4.0.0 | **Date:** March 2026

> **BREAKING CHANGE — Metagraph operators must update before 2026-03-11 or nodes will become incompatible with the network.** The global snapshot format is changing. See upgrade guide below.

**Merkle Patricia Trie (MPT) state proofs** replace the legacy 16-field hash format with a single MPT root hash — enabling efficient state verification and laying the groundwork for light client support. Also includes a **consensus overhaul** (FSM architecture, randomized facilitator selection, gossip optimization), **incremental delegated staking**, ClickHouse structured logging, and numerous bug fixes.

Blog post: https://constellationnetwork.io/blog/tessellation-v4

### Timeline

| Date | Event |
|------|-------|
| **Today** | v4.0.0 release notes published |
| **Tuesday 2026-03-10** | Transition ordinal announced |
| **Wednesday 2026-03-11** | MPT state proofs activate at announced ordinal |

Validated on Testnet (since 2026-02-06) and IntegrationNet (since 2026-02-19) without issue.

### Metagraph Operators — Action Required

Update your Tessellation dependency to **v4.0.0-rc.10** before 2026-03-11 to remain compatible. This is the version with the correct schema changes for the mainnet migration. Key changes: Java 21 runtime, OSGI-INF merge strategy in `build.sbt`, replace `new URL()` with `URI.create().toURL`. No code changes needed for MPT.

Upgrade guide: https://github.com/Constellation-Labs/tessellation/blob/develop/docs/release/metagraph-upgrade-guide.md
Release notes: https://github.com/Constellation-Labs/tessellation/blob/develop/docs/release/v4.0.0-mainnet.md
Release: https://github.com/Constellation-Labs/tessellation/releases/tag/v4.0.0-rc.10
