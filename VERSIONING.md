# Tessellation Versioning System

This document describes the unified versioning system for Tessellation, designed to provide:

- **Unique version per commit** — Every build is uniquely identifiable
- **Commit hash traceability** — Link any artifact back to exact source code
- **CI build indexes** — Track which CI run produced an artifact
- **Maven compatibility** — Valid semver for Sonatype Central publishing
- **Manual override support** — Explicit control when needed via `RELEASE_TAG`

## Version Format

```
<major>.<minor>.<patch>[-<prerelease>][+<metadata>]
```

### Components

| Component | Description | Example |
|-----------|-------------|---------|
| `major.minor.patch` | Semantic version from git tag | `4.1.0` |
| `-prerelease` | Pre-release identifier (optional) | `-rc.1`, `-alpha.3` |
| `+metadata` | Build metadata (optional, not published) | `+3.abc1234.build42` |

### Metadata Format

When not on an exact tag, metadata is appended:

```
+<distance>.<sha>.<build>
```

- **distance** — Number of commits since last tag
- **sha** — Short commit hash (7 chars)
- **build** — `GITHUB_RUN_NUMBER` or `local`

## Version Examples

| Scenario | Git State | Version |
|----------|-----------|---------|
| Tagged release | `v4.1.0` tag on HEAD | `4.1.0` |
| Pre-release | `v4.1.0-rc.1` tag on HEAD | `4.1.0-rc.1` |
| Development | 3 commits after `v4.1.0`, CI build #42 | `4.1.0+3.abc1234.build42` |
| Local dev | 3 commits after `v4.1.0`, local machine | `4.1.0+3.abc1234.local` |
| Dirty working dir | On tag but uncommitted changes | `4.1.0+dirty.local` |
| Dirty + commits | 3 commits after tag, uncommitted changes | `4.1.0+3.abc1234.local` |
| No tags in repo | Fresh clone or no version tags | `0.0.0+notags.abc1234.local` |
| Manual override | `RELEASE_TAG=v5.0.0` set | `5.0.0` |

## Version Priority

The version is determined in this order:

1. **`RELEASE_TAG` environment variable** — Highest priority, explicit override
2. **Git tag on HEAD** — If HEAD is tagged with `vX.Y.Z`
3. **Auto-generated** — Derived from nearest tag + commit metadata

```
RELEASE_TAG=v4.1.0  →  4.1.0 (override wins)
         ↓ (not set)
Git tag v4.1.0 on HEAD  →  4.1.0 (clean release)
         ↓ (no tag on HEAD)  
Nearest tag v4.0.0, 3 commits ago  →  4.0.0+3.abc1234.build42
```

## Cluster Join Fence

The embedded `BuildInfo.version` is hashed into the `versionHash` carried by cluster registration.
Joining peers require an exact match, so an official `v4.1.0-rc.7` build rejects `v4.1.0-rc.6`.
This is a join-time release fence, not an assembly-hash comparison and not a replacement for the
separate deterministic consensus-config fingerprint. `CL_VERSION_HASH` overrides the effective
value with an opaque literal; leave it unset for official releases unless a separately reviewed
recovery procedure explicitly requires it.

## Branch Strategy

```
Feature branches ──► develop (snapshots: 4.1.0+N.hash.buildX)
                         │
                         ▼ (merge, tag v4.1.0-alpha.1)
                    release/testnet (4.1.0-alpha.1)
                         │
                         ▼ (merge, tag v4.1.0-rc.1)
                    release/integrationnet (4.1.0-rc.1)
                         │
                         ▼ (merge, tag v4.1.0)
                    release/mainnet (4.1.0) ──► Sonatype Central
```

### Branch Version Conventions

| Branch | Tag Format | Example Version |
|--------|------------|-----------------|
| `develop` | (none) | `4.0.0+15.abc1234.build87` |
| `release/testnet` | `v4.1.0-alpha.N` | `4.1.0-alpha.1` |
| `release/integrationnet` | `v4.1.0-rc.N` | `4.1.0-rc.1` |
| `release/mainnet` | `v4.1.0` | `4.1.0` |

## Publishing Rules

### Sonatype Central (Maven)

Only **clean tagged versions** are published to Sonatype Central:

| Version | Publishable? | Reason |
|---------|--------------|--------|
| `4.1.0` | ✅ Yes | Clean release tag |
| `4.1.0-rc.1` | ✅ Yes | Clean pre-release tag |
| `4.1.0+3.abc1234.build42` | ❌ No | Contains metadata (not on tag) |
| `4.1.0+dirty.local` | ❌ No | Dirty working directory |
| `99.99.99-SNAPSHOT` | ❌ No | Snapshot versions not accepted |

### Local/Internal Use

All versions can be used locally or published to internal repositories:

```bash
# Local publish (any version)
sbt publishLocal

# Check current version
sbt "show version"
```

## CI/CD Integration

### GitHub Actions

The release workflow automatically:

1. Computes version from conventional commits (`commit-and-tag-version`)
2. Creates and pushes git tag
3. Passes `RELEASE_TAG` to sbt for assembly
4. Publishes SDK to Sonatype Central

```yaml
# .github/workflows/release.yml (excerpt)
- name: Assembly
  run: sbt 'assembly'
  env:
    RELEASE_TAG: v${{ steps.get_version.outputs.VERSION }}
    GITHUB_RUN_NUMBER: ${{ github.run_number }}
```

### Manual Release

To manually trigger a release:

```bash
# Option 1: Tag and push (recommended)
git tag -a v4.1.0 -m "Release 4.1.0"
git push origin v4.1.0

# Option 2: Override version directly
RELEASE_TAG=v4.1.0 sbt assembly
```

## Checking Version

### In sbt

```bash
sbt "show version"
# [info] 4.0.0+3.abc1234.local

sbt "show dynverGitDescribeOutput"
# [info] Some(GitDescribeOutput(GitRef(v4.0.0), GitCommitSuffix(3, abc1234), GitDirtySuffix()))
```

### In CI Logs

The version is printed at the start of each sbt command:

```
[info] GitVersioningPlugin set version=4.1.0+3.abc1234.build42
```

### From Artifacts

JAR filenames include the version:

```
tessellation-sdk-4.1.0.jar           # Release
tessellation-sdk-4.1.0+3.abc1234.build42.jar  # Development
```

## Troubleshooting

### "Version shows 0.0.0+notags..." (sbt)

When using sbt, this happens when:
- No git tags exist in the repository
- Running in a shallow clone (`git fetch --unshallow` to fix)
- Git is not available (`0.0.0+unknown.local`)

Example: `0.0.0+notags.abc1234.local`

### "Version shows 99.99.99-SNAPSHOT" (Docker)

This fallback only appears in Docker scripts (`docker/bin/set-env.sh`) when:
- No git tags matching `v*` exist
- Git is not available in the container
- The `RELEASE_TAG` env var is not set

To fix: Set `RELEASE_TAG=vX.Y.Z` or ensure the Docker build context includes git tags.

### "Version doesn't match expected tag"

Check:
1. Is HEAD exactly on the tag? (`git describe --tags`)
2. Are there uncommitted changes? (`git status`)
3. Is `RELEASE_TAG` env var set? (overrides git)

### "SDK publish failed: not releasable"

The version contains `+metadata`, indicating you're not on a clean tag:

```
Version '4.1.0+3.abc1234.build42' is not releasable to Sonatype Central.
```

Fix: Push a git tag or set `RELEASE_TAG=v4.1.0`

## Implementation Details

### sbt-dynver

We use [sbt-dynver](https://github.com/sbt/sbt-dynver) for git-based version calculation.

Key settings in `build.sbt`:
- `dynverGitDescribeOutput` — Parsed output of `git describe`
- `dynverSonatypeSnapshots` — Disabled (we handle snapshot logic)

### sbt-ci-release

We use the upstream [sbt-ci-release](https://github.com/sbt/sbt-ci-release) plugin (v1.11.2+) which:
- Handles GPG setup for signing
- Manages Sonatype Central publishing (supported since v1.11.0)
- Validates version is releasable before publish
- Brings sbt-dynver, sbt-pgp, and sbt-git as transitive dependencies

Note: sbt-sonatype is declared explicitly in `project/plugins.sbt` (not transitive from sbt-ci-release).

The CI release command is `ci-release` (standard sbt-ci-release command).

### Conventional Commits

Version bumps are determined by commit message prefixes:
- `feat:` → Minor version bump
- `fix:` → Patch version bump
- `BREAKING CHANGE:` or `!:` → Major version bump

## Migration Notes

### From Old System

The old system used:
```scala
ThisBuild / version := sys.env.get("RELEASE_TAG").getOrElse("99.99.99-SNAPSHOT")
```

This caused issues:
- Local builds always showed `99.99.99-SNAPSHOT`
- No way to identify which commit produced an artifact
- Version mismatches between code and reported version

### Compatibility

- `RELEASE_TAG` still works as an override
- Existing CI scripts continue to function
- Justfile commands remain unchanged

## References

- [Semantic Versioning 2.0.0](https://semver.org/)
- [sbt-dynver](https://github.com/sbt/sbt-dynver)
- [Conventional Commits](https://www.conventionalcommits.org/)
- [Sonatype Central Publishing](https://central.sonatype.org/publish/)
