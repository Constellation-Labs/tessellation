# Merkle Patricia Trie (MPT) Documentation

This directory contains documentation for Tessellation's Merkle Patricia Trie implementation.

## Contents

- [Architecture Overview](./architecture.md) - High-level design and components
- [Data Structures](./data-structures.md) - Node types, keys, and encoding
- [Proof System](./proof-system.md) - Generating and verifying proofs
- [Integration Guide](./integration.md) - How MPT integrates with snapshots
- [API Reference](./api-reference.md) - Key types and methods

## Quick Start

The MPT provides cryptographic state commitments for Tessellation's global snapshot state. It enables:

- **State Integrity**: Single root hash commits to entire state
- **Efficient Proofs**: O(log n) inclusion proofs for any key
- **Batch Operations**: Prove multiple keys efficiently
- **Range Queries**: Prove all keys in a range

## Diagrams

Visual diagrams are embedded in the documentation using Mermaid and Graphviz formats.

To render:
- **Mermaid**: Most markdown viewers render these natively
- **Graphviz**: Use `dot -Tpng diagram.dot -o diagram.png` or online viewers
