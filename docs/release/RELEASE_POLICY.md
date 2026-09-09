# Release Policy

## Overview

This document defines the release process for Tessellation software updates across Constellation network environments. It covers notice periods, announcement requirements, and coordination procedures for jar releases, network upgrades, and feature epoch activations.

Releases flow through environments in sequence: **Testnet -> IntegrationNet -> MainNet**. Each successive environment requires longer notice periods and more complete documentation to give node operators adequate preparation time.

- **Testnet** - Initial validation environment, no notice requirements
- **IntegrationNet** - Staging ground for external integrators, 3-day notice window
- **MainNet** - Production network, 7-day advance notice with complete documentation

All network upgrades require coordination with the auto-restart lambda. A source
node reaching `Ready` is only a startup observation; it is **not** permission to
re-enable automated restart or rollback actions. The release gate is defined in
[Mandatory pre-stop evidence and monitoring gate](#mandatory-pre-stop-evidence-and-monitoring-gate).

Before performing a release or merging a PR with breaking changes, ensure you have this document and its references on hand and understand the purpose of each stage.

| Environment | Notice Period | Breaking Changes | Feature Epoch | Discord |
|-------------|---------------|------------------|---------------|---------|
| Testnet     | N/A           | N/A              | N/A           | Network Update |
| IntNet      | 3 days        | Minimal Docs     | R+1 day       | All Stages |
| MainNet     | 7 days        | Complete         | R+3 days      | All Stages |

## References

- Network Monitoring Service (auto-restart lambda)
- Network Monitoring dashboards
- Release Process (internal runbook)
- [Tessellation GitHub Releases](https://github.com/Constellation-Labs/tessellation/releases)
- [Conventional Commits](https://www.conventionalcommits.org/) (see also [`docs/adr/0015-conventional-commits.md`](../adr/0015-conventional-commits.md))

## Mandatory pre-stop evidence and monitoring gate

Before any planned or incident-driven stop, rollback, restart, or hard kill,
disable automated restart/rollback actions and preserve a timestamped evidence
bundle. At minimum, capture the following from every controlled source and any
node involved in the incident:

- the active application log, every available rotated application log, and HTTP
  or access logs;
- the system/service journal covering the incident and preceding startup,
  service status, process exit status or signal, and restart counters;
- any heap dump, core dump, JVM fatal-error file, or other crash artifact that
  already exists;
- the redacted effective environment, rendered configuration, launch command,
  service/unit definition, and deployment manifest;
- the jar digest, advertised version and `versionHash`,
  `deterministicConfigHash`, and consensus schema/configuration version;
- the selected anchor ordinal and hash, direct source observations used to
  establish it, and the Snapshot Streaming/database tip and hash; and
- filename, size, link-count, and digest manifests for snapshot indexes,
  consensus locks/journals, outcome sidecars, and other recovery sidecars.

Store the bundle outside live log and snapshot-rotation directories. Source log
retention has been observed to fall below 24 hours under load, with the busiest
restart/incident window disappearing first. Pull before stopping, preserve
periodic control bundles during a soak, and treat every incident bundle as
durable release evidence rather than a cleanup candidate. If host safety makes
a complete capture impossible, record exactly which evidence was unavailable
and why before proceeding.

During a normal coordinated Global L0 cold restart, monitoring may alert but must not
stop, restart, or roll back nodes while
`dag_consensus_normal_first_round_alignment_held == 1`. Re-enable those actions
only after the canonical first successor is accepted and
`dag_consensus_signing_finality_audit_current_finality_margin > 0`. That gauge
is the canonical parent proof signers intersected with the current signing
committee, minus its current finality floor. `Ready`, process uptime, and a flat
tip by themselves do not satisfy this gate.

An explicit recovery-seed restart has additional release conditions: remove
`CL_GL0_RECOVERY_SEED_COMMITTEE` from every selected source launch environment;
at/after v35, accept canonical `R+2` and require
`dag_consensus_recovery_seed_boundary_publicly_durable == 1`; and prove Snapshot
Streaming follows the same source-agreed lineage before enabling automated
actions. See
[Global L0 trusted recovery seed committee](../operations/global-l0-recovery-seed-committee.md).

## Functionality Definitions

### Release Artifacts Construction

CI-driven process creating binaries, JARs, and libraries (Maven/Sonatype). This is the first step of a release, producing artifacts coordinated with a specific software version. See `.github/workflows/release.yml` and `project/TessellationCiRelease.scala`.

### Network Update

Manual triggering of AWS node updates representing a change in the underlying software/jar version. This often creates a hard fork, which should be avoided when possible through the use of feature flags.

### Ordinal / Epoch Feature Flags

The preferred mechanism for introducing breaking changes. Gives node operators and metagraph developers sufficient time to update versions while still connected with an earlier jar version.

- **Epoch-based flags** (preferred) - Enable breaking behavior at a specific approximate point in time. Epoch is a vector clock that loosely approximates actual time.
- **Ordinal-based flags** - Should be avoided when possible, as ordinals cannot accurately proxy for time over longer periods, but may be necessary due to technical constraints.

Configuration lives in `modules/node-shared/src/main/resources/application.conf`. The current primary ordinal-gate surface is the `fields-added-ordinals` block, a `Map[AppEnvironment, SnapshotOrdinal]` family plus the `dust-sweeps` `Map[AppEnvironment, SortedMap[SnapshotOrdinal, DustSweep]]`. Live sub-keys include `sc-fee-balance-from-context`, `dust-sweeps`, `set-sum-fix`, `fixing-allow-spend-and-token-lock-validation`, `sub-trie-roots`, `delegated-rewards-full-committee`, `fee-transaction-security`, and the historical migration gates. Older keys like `last-legacy-state-proof-ordinal` and `incremental-delegated-staking-starting-ordinal` still exist but are no longer where new gates land.

Several `fields-added-ordinals` gates require the deploy-time rule that **the chain must cross the gate ordinal only after the new jar is live cluster-wide** (a too-early crossing on the old jar misses the gated behaviour; for the dust sweep, a missed sweep is not re-attempted until a rollback re-crosses the ordinal). See the gate-setting checklist in [`v4-launch-runbook.md`](v4-launch-runbook.md).

### Rollback

For non-mainnet environments, sometimes required to rollback to a much earlier snapshot ordinal due to breaking changes or reversions. This is a manual process; contact @Marcus Sousa for the procedure.

### Snapshot Streaming Consistency

Some breaking changes interfere with the state transition application function in snapshot streaming (a Tessellation-driven dependency). For upgrades that introduce such breaking changes, **snapshot streaming must be updated BEFORE the introduction of any breaking changes**, otherwise the network will halt.

### Announcements

Discord is the primary mechanism for announcements. Most automatic updates rely on GitHub release integrations. Slack is used for internal coordination and testing.

## Release Stages

Stages proceed in order, with exceptions only for emergency maintenance or required debugging.

### 1. Merge to Develop

Requires PR approval only. Does not deploy code anywhere manually. Breaking changes should ideally be documented as part of the merged commits using conventional commit format, but documentation can be deferred to later stages.

### 2. Testnet Release

- No prior announcement required
- Merge to `release/testnet` branch
- Rollback if necessary
- Discord announcement preferred whenever possible
- For rapid iteration, prefer to announce a series of releases or the final one

### 3. IntegrationNet Release

- **3-day advance announcement** in Discord for the network jar update
- Announcement when jar is released
- A commit **MUST** be included with breaking changes following [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) (see `docs/adr/0015-conventional-commits.md`)
- Priority: ensure metagraph developers are aware of changes that will halt or break their connection to GL0
- Feature flags for enabling specific behavior must give at least 1 day of notice
- Announcements must be made when feature flags are triggered

### 4. MainNet Release

- **7-day advance announcement** in Discord of intended date
- Announcement again when release happens
- Must include breaking change commits with additional detail beyond IntNet documentation
- Announcement for feature flags
- All prior IntNet steps must be followed

## Discord Announcement Types

All announcements should be posted to `#announcements` channel on Discord. Additional ad-hoc announcements may be sent to specific metagraph developers or internal channels at the discretion of the feature developer.

### Jar Release Announcement

Post when a new Tessellation version is published to GitHub releases, a network upgrade is happening, or a feature flag is being enabled. Must include (where appropriate for environment/stage):

- Expected network update date
- Expected dates/epochs/ordinals of all feature flags
- List of breaking changes
- Link to release information
- Environment being affected (testnet, integrationnet, mainnet)

### Network Update

The point in time when AWS nodes are restarted with new software version. Typically a hard forking event. Announcement must include:

- Whether this is a hard fork or not
- All prior references to the release
- Next dates of expected feature flags / breaking changes

### Feature Flag Enabled

Primarily for breaking changes. Must include prior reference to the release it is a part of. Can also be used for normal feature announcements. This represents the "final" step of the release. The next expected feature flag should be emphasized, or indicate this is the completion of the release.
