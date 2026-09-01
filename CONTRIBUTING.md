# How to Contribute

## Prerequisites

- Java 21
- SBT
- Docker (for running tests)

## Development Setup

### IDE Setup

For IntelliJ scalafmt integration, see [JetBrains documentation](https://www.jetbrains.com/help/idea/work-with-scala-formatter.html).

## Code Contributions

### Repository Fork

1. Fork via https://github.com/Constellation-Labs/tessellation/fork

2. Clone your fork and add upstream:

```sh
git clone https://github.com/<your-github-account>/tessellation
cd tessellation
git remote add upstream https://github.com/Constellation-Labs/tessellation
```

### Feature Branch

Create a branch for your work:

```sh
git checkout -b 747-update-contrib
```

### Keeping Up to Date

Sync your branch with upstream regularly:

```sh
git fetch upstream
git rebase upstream/develop
```

### Pull Request

1. Rebase with upstream before pushing
2. Push your branch:

```sh
git push -u origin 747-update-contrib
```

3. Create a PR via GitHub UI

## Coding Standards

### Public-network protocol baseline

IntegrationNet, Testnet, and Mainnet have all permanently crossed the historical Kryo-to-JSON
snapshot serialization boundary. New protocol functionality targets the JSON-serde era:

- do not add Kryo registrations, fallback readers, frozen Kryo projections, or Kryo-to-current
  compatibility tests solely for new functionality;
- do not constrain a new JSON-era schema design around hypothetical rollback or replay across the
  retired Kryo boundary;
- retain existing Kryo readers only where already-supported historical data or tooling still uses
  them, and do not make new functionality depend on those readers; and
- continue to apply ordinary schema-version, deterministic hashing, activation-ordinal, and
  coordinated-rollout review to JSON-era changes. Passing the Kryo boundary does not waive those
  requirements.

`GlobalSnapshot` is the frozen genesis-era full diff+state type. New protocol fields must not be
added to it. Versioned incremental snapshots may evolve behind their coordinated activation gate;
compact certified checkpoints, if introduced, must be standalone authenticated manifests paired
with immutable combined incremental checkpoints.

Public releases use a permissioned, allowlisted topology and a coordinated full-cluster cold
restart: one controlled source runs `run-rollback`, and every other node runs `run-validator` on
the same distinctly versioned artifact. Do not design recovery around a permissionless-network
threat model or require community validators to authorize an operator recovery plan.

### Style Guide

- Follow existing code style in the repository
- Run `sbt runLinter` before committing (scalafmt + scalafix)

### Commands

```sh
sbt compile      # Compile the project
sbt test         # Run tests
sbt runLinter    # Auto-format code
```

### Commit Messages

Conventional commits are required (enforced by commitlint).

Format: `type: description`

Types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `build`, `ci`, `perf`, `style`, `revert`

Examples:
- `feat: add new validation endpoint`
- `fix: resolve race condition in consensus`
- `refactor: simplify block processing logic`
