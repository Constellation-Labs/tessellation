# How to Contribute

## Prerequisites

- Java 21
- SBT
- Docker (for running tests)

## Development Setup

### IDE Setup

For IntelliJ scalafmt integration, see [JetBrains documentation](https://www.jetbrains.com/help/idea/work-with-scala-formatter.html).

### Git Hooks

Install the repository hooks once after cloning:

```sh
./.githooks/install
```

The pre-push hook runs `scalafmtCheckAll`, the same formatting gate that CI runs
first. If it fails, run `sbt scalafmtAll`, commit the formatting change, and push
again. Git's standard `--no-verify` option remains available for exceptional cases.

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

### Style Guide

- Follow existing code style in the repository
- Run `sbt runLinter` before committing (Scalafix imports, then Scalafmt)

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
