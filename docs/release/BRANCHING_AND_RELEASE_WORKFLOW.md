# Branching and Release Workflow

This document describes the branching strategy, release workflow, and operational procedures for Tessellation. It complements [VERSIONING.md](../../VERSIONING.md) which covers version format and tooling.

## Branch Hierarchy

```
feature/*  ──►  develop  ──►  release/testnet  ──►  release/integrationnet  ──►  release/mainnet
               (source of     (alpha tags)          (rc tags)                    (stable tags)
                truth)
```

Code flows **one direction only**: left to right. Each release branch receives code exclusively by merging from the branch to its left.

### Branch Roles

| Branch | Purpose | Tag Format | Deployment |
|--------|---------|------------|------------|
| `develop` | Integration of all features and fixes | None (auto-versioned) | — |
| `release/testnet` | Pre-release validation | `v4.x.y-alpha.N` | S3 → Testnet |
| `release/integrationnet` | Stability validation before mainnet | `v4.x.y-rc.N` | GitHub Release → Integrationnet + SDK to Sonatype |
| `release/mainnet` | Production releases | `v4.x.y` | GitHub Release → Mainnet + SDK to Sonatype |

### Version Progression Example

```
develop:    feat A, feat B, fix C merged via PRs
                │
                ▼  (merge develop → release/testnet)
testnet:    v4.1.0-alpha.0  →  (more merges)  →  v4.1.0-alpha.1
                │
                ▼  (merge develop → release/integrationnet)
intnet:     v4.1.0-rc.0  →  (more merges)  →  v4.1.0-rc.1
                │
                ▼  (merge develop → release/mainnet)
mainnet:    v4.1.0
```

## Core Rules

### 1. Develop is the single source of truth

Every code change enters through `develop` via a pull request. Release branches are downstream consumers of develop — never the reverse.

### 2. No direct commits to release branches

All release branches receive code **only** by merging from develop (or in rare cases, from the branch directly upstream). Never commit, cherry-pick, or push directly to a release branch.

**Why:** Direct commits create "branch drift" — duplicate commits with different SHAs that make future merges impossible without conflicts. This is exactly what led to the February 2026 branch realignment where 16+ cherry-picked commits had to be triaged across `release/testnet` and `release/integrationnet`.

### 3. Environment-specific configuration

Network-specific tuning must be represented by shared configuration on `develop`, **not** by
branch-specific code changes. A value can be supplied at deployment time only when its HOCON key has
an explicit environment-variable override.

The `application.conf` files support environment variable overrides using the HOCON `${?VAR}` pattern:

```hocon
fanout = 2
fanout = ${?CL_GOSSIP_PEER_ROUND_FANOUT}
```

This allows those specifically bound parameters to differ by network without diverging the
codebase. It does not make every HOCON value deploy-time configurable.

`fields-added-ordinals` currently has no `${?VAR}` bindings. Its hard-fork activation values are
packaged into the assembly and must be finalized on `develop` before the release artifact is built.
Ordinal changes follow the normal PR and forward-merge process; they cannot be injected through
environment variables or Kubernetes ConfigMaps.

### 4. Tags are created by CI, not manually

The release workflows (`release.yml`, `testnet-release.yml`) automatically compute the next version from conventional commits and create the appropriate git tag. Manual tagging should only be used for one-time bootstrap operations or emergency overrides.

## Standard Workflows

### Releasing to Testnet

1. Ensure `develop` has the changes you want to test
2. Merge develop into `release/testnet`:
   ```bash
   git checkout release/testnet
   git merge origin/develop
   git push origin release/testnet
   ```
3. The `testnet-release.yml` workflow triggers automatically:
   - Runs linter and tests
   - Computes next `alpha.N` version via `commit-and-tag-version`
   - Creates and pushes the git tag
   - Builds assembly JARs with the tagged version
   - Signs and uploads artifacts to S3

### Promoting to Integrationnet

1. Verify testnet is stable with the current alpha release
2. Merge develop into `release/integrationnet`:
   ```bash
   git checkout release/integrationnet
   git merge origin/develop
   git push origin release/integrationnet
   ```
3. The `release.yml` workflow triggers automatically:
   - Runs linter and tests
   - Computes next `rc.N` version
   - Creates git tag, builds, signs, and creates a draft GitHub Release

### Promoting to Mainnet

1. Verify integrationnet is stable
2. Merge develop into `release/mainnet`:
   ```bash
   git checkout release/mainnet
   git merge origin/develop
   git push origin release/mainnet
   ```
3. The `release.yml` workflow triggers:
   - Computes next stable version (e.g., `4.1.0`)
   - Creates git tag, builds, signs
   - Creates draft GitHub Release
   - Publishes SDK to Sonatype Central

## Handling Urgent Hotfixes

Even for urgent production issues, the process flows through develop first.

### Procedure

1. **Create a fix branch from develop:**
   ```bash
   git checkout -b fix/urgent-issue origin/develop
   # ... make the fix ...
   git push origin fix/urgent-issue
   ```

2. **Open a PR to develop.** Get expedited review if needed.

3. **Merge to develop**, then immediately merge develop forward to the affected release branch:
   ```bash
   git checkout release/integrationnet
   git merge origin/develop
   git push origin release/integrationnet
   ```

4. The release workflow triggers and produces the next version automatically.

### Why not cherry-pick directly?

Cherry-picking creates a new commit with a different SHA. This means:
- Git can no longer track that the fix exists on both branches
- Future merges may conflict on the same lines
- `git log` shows duplicate entries with different hashes
- Version tooling (`commit-and-tag-version`) may double-count the change

By always going through develop, every release branch stays as a strict superset of develop's history.

## Recovery: Realigning a Drifted Branch

If someone accidentally commits directly to a release branch:

### Step 1: Identify the drift

```bash
# Check which commits exist on the release branch but not develop
git log --oneline origin/develop..origin/release/integrationnet
```

### Step 2: Triage each commit

For each unique commit, determine:
- **Real fix needed on develop?** → PR it to develop
- **Environment-specific tuning?** → Drop it, use env vars instead
- **Superseded by a develop commit?** → Drop it

### Step 3: Realign

Once develop has all necessary changes:

```bash
# Reset the release branch to develop
git push origin origin/develop:refs/heads/release/integrationnet --force-with-lease
```

`--force-with-lease` ensures no one else pushed to the branch since your last fetch.

### Step 4: Verify

```bash
# Should show 0 commits
git rev-list --count origin/develop..origin/release/integrationnet
```

## Drift Detection

Run this periodically (or in CI) to catch drift early:

```bash
for branch in release/testnet release/integrationnet release/mainnet; do
  count=$(git rev-list --count origin/develop..origin/$branch 2>/dev/null)
  if [ "$count" -gt 0 ]; then
    echo "WARNING: $branch has $count commit(s) not on develop"
    git log --oneline origin/develop..origin/$branch
  else
    echo "OK: $branch is aligned with develop"
  fi
done
```

A healthy state is: release branches have **0 commits** not on develop (they may be *behind* develop, but never *ahead* with unique commits).

## Recommended Branch Protection Rules

The following GitHub branch protection settings enforce these workflows:

### `release/integrationnet` and `release/mainnet`

| Setting | Value | Rationale |
|---------|-------|-----------|
| Require pull request before merging | **Off** | Merges from develop are direct pushes, not PRs |
| Restrict who can push | **Enabled** — admin team only | Prevents accidental pushes by non-release managers |
| Allow force pushes | **Admins only** | Needed for planned realignment; restricted to prevent accidents |
| Require status checks to pass | **Optional** | The release workflow runs its own checks; gate at develop instead |

### `release/testnet`

| Setting | Value | Rationale |
|---------|-------|-----------|
| Restrict who can push | **Enabled** — admin + dev team | Slightly broader access for faster iteration |
| Allow force pushes | **Admins only** | Same as above |

### `develop`

| Setting | Value | Rationale |
|---------|-------|-----------|
| Require pull request before merging | **Enabled** | All changes go through code review |
| Require status checks to pass | **Enabled** — linter + tests | Gate quality at the source |
| Require approvals | **1 approval minimum** | Standard review process |
| Do not allow bypassing | **Enabled** | Even admins go through PRs |

## FAQ

### Can I push a quick config change directly to testnet?

No. Even configuration changes should go through develop. Use a deployment-time environment
variable only when the relevant HOCON key explicitly supports one (see Rule 3). Assembly-packaged
values such as `fields-added-ordinals` require a normal change through `develop`.

### What if develop has changes I don't want on testnet yet?

Use shared feature configuration, including per-environment ordinal maps for deterministic consensus
behavior, not branch divergence. If a feature truly isn't ready for any release branch, it shouldn't
be on develop yet; use a long-running feature branch instead.

### Can I skip testnet and go straight to integrationnet?

Yes — the branches don't depend on each other. You merge develop into whichever release branch you want. The hierarchy is a recommended promotion path, not a strict pipeline.

### What happens if the release workflow fails after creating a tag?

The tag will exist as an "orphan" (no corresponding release artifacts). Delete the tag and re-push to the release branch:
```bash
git push origin :refs/tags/v4.1.0-rc.3   # delete remote tag
git tag -d v4.1.0-rc.3                    # delete local tag
# Re-trigger by pushing a new commit or force-pushing
```

### How do I check what version will be produced next?

```bash
# For testnet (alpha):
npx commit-and-tag-version --dry-run --prerelease alpha | grep 'tagging release'

# For integrationnet (rc):
npx commit-and-tag-version --dry-run --prerelease rc | grep 'tagging release'

# For mainnet (stable):
npx commit-and-tag-version --dry-run | grep 'tagging release'
```
