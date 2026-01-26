# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Tessellation is the Constellation Network Node Software - a DAG (Directed Acyclic Graph) based distributed ledger with Layer 0 (L0) and Layer 1 (L1) validators. Written in Scala 2.13, designed for Kubernetes deployment.

## Build Commands

```bash
sbt compile              # Compile the project
sbt test                 # Run all tests
sbt runLinter            # Format code (scalafmt + scalafix)

# Run tests in specific module
sbt "dagL0/test"
sbt "nodeShared/test"

# Assembly (create JARs)
sbt dagL0/assembly
sbt dagL1/assembly
```

## Docker-based Development

```bash
just test                 # Full test suite with Docker
just test --skip-assembly # Skip compilation, reuse JARs
just up                   # Start test environment
just down                 # Teardown environment
just check                # Lint + format check + tests
```

## Code Quality

Before committing, run:
```bash
sbt runLinter   # Auto-format with scalafmt and scalafix
```

CI runs: `sbt --error 'scalafixAll --check --rules OrganizeImports;scalafmtCheckAll;test'`

Conventional commits required (enforced by commitlint). Format: `type: description`
- Types: feat, fix, refactor, test, docs, chore, build, ci, perf, style, revert

## Architecture

```
modules/
├── shared        # Core data structures, crypto, serialization
├── kernel        # Recursion schemes (Droste), core abstractions
├── keytool       # Key management and cryptography
├── wallet        # Wallet operations
├── node-shared   # P2P networking, consensus, metrics, gossip
├── dag-l0        # Layer 0 validator - global DAG consensus
├── dag-l1        # Layer 1 validator - metagraph consensus
├── currency-l0   # Currency logic for L0
├── currency-l1   # Currency logic for L1
├── sdk           # SDK for custom metagraph extensions
├── rosetta       # Blockchain data standardization
├── tools         # CLI utilities
└── test-shared   # Test utilities and generators
```

**Module Dependencies:**
- `dagL0` depends on: kernel, shared, keytool, nodeShared
- `dagL1` depends on: kernel, shared, nodeShared
- `currencyL0/L1` depend on: their respective dag layers + nodeShared
- `sdk` depends on all core modules (provided scope)

## Tech Stack

- **Scala 2.13.18** with **Java 21** (enforced at build time)
- **Cats-Effect 3** for async/IO
- **FS2** for streaming
- **HTTP4s** (Ember) for HTTP server/client
- **Circe** for JSON serialization
- **Weaver** for testing (cats-effect based)
- **BouncyCastle** for cryptography
- **Refined types** for validation

## Testing

Tests use Weaver framework with `MutableIOSuite` base class:

```scala
object MySuite extends MutableIOSuite with Checkers {
  override type Res = (Dependency1, Dependency2)

  override def sharedResource: Resource[IO, Res] =
    for {
      dep1 <- createDep1
      dep2 <- createDep2
    } yield (dep1, dep2)

  test("my test") { case (dep1, dep2) =>
    // test implementation
  }
}
```

Test utilities are in `modules/test-shared/`.

## Key Patterns

- Resource-based dependency management with `Resource[IO, _]`
- Refined types for compile-time validation
- Monocle optics for data transformation
- Droste for recursion schemes
- Newtype for zero-cost type wrappers

## Codebase Overview

Tessellation implements a hierarchical DAG consensus with L0 (global) aggregating L1 (metagraph) blocks. The largest module is `node-shared` (419k tokens) providing consensus FSM, anti-entropy gossip, and cluster management. Core data structures (transactions, blocks, snapshots, Merkle Patricia Tries) live in `shared`. Currency modules extend dag-l0/l1 with metagraph-specific logic and extension points for custom data applications.

**Consensus flow**: L1 creates blocks → Currency-L0 creates snapshots → Global-L0 creates global snapshots with all metagraph state.

For detailed architecture, file purposes, and navigation guides, see [docs/CODEBASE_MAP.md](docs/CODEBASE_MAP.md).
