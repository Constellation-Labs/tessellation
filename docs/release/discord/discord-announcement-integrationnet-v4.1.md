# Discord Announcement Draft - IntegrationNet v4.1.0

> Copy the section below for posting to `#announcements`. Post by **Friday 2026-07-10** to satisfy the
> 3-day advance-notice requirement for the 2026-07-13 network update.

---

## Tessellation v4.1.0 - IntegrationNet Release

**Environment:** IntegrationNet
**Version:** v4.1.0
**Network Update:** Monday 2026-07-13 (coordinated cold restart)
**Hard fork:** Yes - coordinated cold restart, all nodes on the same jar and config

### What's Changing

This release rebuilds the **Global L0 consensus engine** on a leader-based, **partially synchronous
Byzantine Fault Tolerant (BFT)** design. It is the outcome of the stabilization work that followed the
v4.0.0 MainNet incident: under MainNet-scale load, node resource degradation outpaced a consensus
liveness model that was not built to absorb it, and the network could not restabilize.

v4.1.0 makes consensus stay live through degradation: a stalled round advances instead of waiting,
committee membership is agreed via certified on-chain evidence rather than local guesswork, lagging
nodes have a reliable path back to the frontier, and bulk state transfer is separated from consensus so
catching up a slow peer cannot starve the consensus loop.

### Release Timeline

| Date | Event |
|------|-------|
| **Since 2026-06-16** | Validating on Testnet (v4.1.0-alpha.159) |
| **Friday 2026-07-10** | Advance notice (this announcement) |
| **Monday 2026-07-13** | IntegrationNet coordinated cold restart to v4.1.0 |

> ⚠️ **This is a coordinated cold restart, not a rolling upgrade.** All nodes must run the same jar and
> consensus config. A node on a mismatched version or config is refused at the connection handshake.

### Deploy Notes for Node Operators

- Full, all-or-nothing coordinated cold restart from a recent agreed checkpoint snapshot (not a genesis
  replay). No mixed-version overlap window.
- Source / priority peers come up first and reach `Ready` before the rest join.
- Availability now directly determines consensus participation: a healthy node participates on a level
  playing field; a node that is behind is routed around until it catches up. No slashing.
- Runbook: https://github.com/Constellation-Labs/tessellation/blob/develop/docs/release/v4-launch-runbook.md

### Breaking Changes

- **Consensus schema version bump** and a consensus config-hash handshake fence: mixed-version clusters
  partition, so the upgrade is all-or-nothing.
- **No Java change** from v4.0.0 (Java 21 / Scala 2.13.18 unchanged; IntegrationNet is already on the
  v4.0.0-rc.10 line).

<!-- TODO(confirm before posting): metagraph-operator impact. The consensus rebuild is confined to
     Global L0 and metagraph snapshot formats are unaffected, but per Release Policy we MUST confirm
     whether metagraph nodes need to update their Tessellation dependency to v4.1.0 to remain connected
     to the v4.1.0 GL0, or whether v4.0.0-rc.10 metagraph nodes stay compatible. Fill in the section
     below once confirmed. -->

### For Metagraph Operators

The consensus rebuild is confined to Global L0; metagraph (currency) snapshot formats are not changed.
**[CONFIRM] Whether a Tessellation dependency update to v4.1.0 is required to remain connected to the
upgraded GL0 will be stated here before this notice is posted.** If your metagraph connects to
IntegrationNet GL0, watch this channel for the confirmed guidance.

### Release Notes

https://github.com/Constellation-Labs/tessellation/blob/develop/docs/release/v4.1.0-testnet.md

### Release

https://github.com/Constellation-Labs/tessellation/releases

---

> **Note:** Per release policy, IntegrationNet releases require 3-day advance notice; this announcement
> serves as that notice for the 2026-07-13 network update. MainNet promotion will follow with a 7-day
> advance announcement after IntegrationNet validation.
