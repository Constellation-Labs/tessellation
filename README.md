# Tessellation

![build](https://img.shields.io/github/actions/workflow/status/Constellation-Labs/tessellation/release.yml?label=build)
![Dynamic JSON Badge](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fl0-lb-testnet.constellationnetwork.io%2Fnode%2Finfo&query=%24.version&label=TestNet)
![Dynamic JSON Badge](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fl0-lb-integrationnet.constellationnetwork.io%2Fnode%2Finfo&query=%24.version&label=IntegrationNet)
![Dynamic JSON Badge](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fl0-lb-mainnet.constellationnetwork.io%2Fnode%2Finfo&query=%24.version&label=MainNet)

Constellation Network Node Software - a DAG-based distributed ledger with Layer 0 (L0) and Layer 1 (L1) validators.

## Overview

Tessellation implements a hierarchical DAG consensus where L1 metagraphs create blocks that L0 aggregates into global snapshots. This architecture enables scalable, parallel processing of transactions across multiple metagraphs while maintaining a unified global state.

Written in Scala 2.13.

## Tech Stack

- **Scala 2.13.18** with **Java 21**
- **Cats-Effect 3** - Async/IO
- **FS2** - Streaming
- **HTTP4s** (Ember) - HTTP server/client
- **Circe** - JSON serialization
- **Weaver** - Testing framework

## Quick Start

### Prerequisites

- Java 21
- SBT
- Docker

### Development with Docker (Recommended)

```bash
just test              # Full test suite
just test --skip-assembly  # Skip compilation, reuse JARs
just up                # Start environment
just down              # Stop environment
just check             # Lint + tests (CI equivalent)
```

### SBT Commands

```bash
sbt compile            # Compile
sbt test               # Run tests
sbt runLinter          # Format code (scalafmt + scalafix)
sbt dagL0/assembly     # Build L0 JAR
sbt dagL1/assembly     # Build L1 JAR
```

## Project Structure

```
modules/
├── shared        # Core data structures, crypto, serialization
├── node-shared   # P2P networking, consensus, gossip
├── dag-l0        # Layer 0 validator (global consensus)
├── dag-l1        # Layer 1 validator (metagraph consensus)
├── currency-l0   # Currency metagraph L0
├── currency-l1   # Currency metagraph L1
├── sdk           # SDK for metagraph development
└── ...           # keytool, wallet, rosetta, tools
```

For detailed architecture, see [docs/CODEBASE_MAP.md](docs/CODEBASE_MAP.md).

## Documentation

- [Metagraph Development](https://docs.constellationnetwork.io/metagraph-development/)
- [Network APIs](https://docs.constellationnetwork.io/network-apis/)
- [Run a Validator Node](https://docs.constellationnetwork.io/run-a-node/)
- [Full Documentation](https://docs.constellationnetwork.io)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

Conventional commits required. Run `sbt runLinter` before committing.

## License

[Apache License 2.0](LICENSE)
