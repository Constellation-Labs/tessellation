# MPT Integration Guide

This document explains how the MPT integrates with Tessellation's snapshot system.

## Release Status

| Network | Status | Since | Notes |
|---------|--------|-------|-------|
| **TestNet** | ✅ Running | 2026-02-06 | Stable, ordinal 3,070,000 |
| **IntegrationNet** | 🚀 Target | TBD | This release |
| **MainNet** | 📋 Planned | TBD | After IntegrationNet validation |

### Transition Timeline

The release follows a staged approach to give metagraph developers time to update:

| Event | Timing | Ordinal Setting |
|-------|--------|-----------------|
| **Version Release** | Day 0 | `last-legacy-state-proof-ordinal = Long.MaxValue` (inactive) |
| **Ordinal Announcement** | Switchover - 1 day | Specific ordinal announced |
| **State Proof Transition** | ~Day 0 + 1 week | MPT activates at announced ordinal |

### Transition Ordinal

The cutoff ordinal for MPT activation determines when:
- Nodes switch from legacy per-field hash proofs to MPT root proofs
- Validators require the new proof format

**IntegrationNet:** TBD (will be announced 1 day before transition)  
**TestNet:** 3,070,000 (active)  
**MainNet:** Not scheduled

## Snapshot State Flow

```mermaid
flowchart TB
    subgraph "Block Processing"
        TXS[Transactions]
        BLOCKS[Blocks]
        STATE[State Updates]
    end
    
    subgraph "Snapshot Creation"
        ACC[StateChangesAccumulator]
        GSI[GlobalSnapshotInfo]
        CONV[GlobalStateConverter]
    end
    
    subgraph "MPT Layer"
        STORE[MptStore]
        PROD[Producer]
        MPT[MerklePatriciaTrie]
        ROOT[MptRoot]
    end
    
    subgraph "State Proof"
        GSP[GlobalSnapshotStateProof]
        SNAP[GlobalSnapshot]
    end
    
    TXS --> BLOCKS --> STATE
    STATE --> ACC
    ACC --> CONV
    CONV --> STORE
    STORE --> PROD
    PROD --> MPT
    MPT --> ROOT
    ROOT --> GSP
    GSI --> GSP
    GSP --> SNAP
```

## State to MPT Pipeline

### Step 1: Accumulate Changes

During block processing, state changes accumulate:

```scala
case class StateChangesAccumulator(
  balances: SortedMap[Address, Balance],
  lastTxRefs: SortedMap[Address, TransactionReference],
  // ... other state changes
  removedAllowSpendKeys: Set[(Option[Address], Address)],
  // ... removal tracking
)
```

### Step 2: Convert to Key-Value Pairs

The converter transforms state to `Map[GlobalStateKey, Json]`:

```scala
import GlobalStateConverter.syntax._

// Full state conversion
val entries: F[Map[GlobalStateKey, Json]] = 
  globalSnapshotInfo.allStateEntries[F]

// Incremental from accumulator
val delta: F[Map[GlobalStateKey, Json]] = 
  accumulator.toStateEntries[F]
```

### Step 3: Build/Update MPT

```scala
// Stateless: build from scratch
val trie: F[Either[MerklePatriciaError, MerklePatriciaTrie]] = entries.flatMap { kv =>
  val hexMap = kv.map { case (key, json) => (key.toHexString, json) }
  MerklePatriciaTrie.makeParallel[F, Json](hexMap)
}

// Stateful: incremental updates
mptStore.syncFromStateChanges(accumulator, ordinal)
val root: F[Either[MerklePatriciaError, MptRoot]] = producer.build.map(_.map(_.rootHash))
```

### Step 4: Create State Proof

```scala
val stateProof: F[GlobalSnapshotStateProof] = 
  globalSnapshotInfo.stateProof(ordinal)
// or with pre-built producer:
  globalSnapshotInfo.stateProof(producer, ordinal)
```

## State Proof Selector

The system supports both legacy and MPT formats:

```mermaid
flowchart TB
    ORD[Snapshot Ordinal]
    SEL{StateProofSelector}
    LEG[Legacy Format<br/>Per-field hashes]
    MPT[MPT Format<br/>Single root hash]
    
    ORD --> SEL
    SEL -->|ordinal < cutoff| LEG
    SEL -->|ordinal >= cutoff| MPT
```

### Configuration

```scala
trait StateProofSelector {
  def select(ordinal: SnapshotOrdinal): StateProofFormat
}

// Implementation determines cutoff
class CutoffBasedSelector(cutoffOrdinal: SnapshotOrdinal) extends StateProofSelector {
  def select(ordinal: SnapshotOrdinal): StateProofFormat =
    if (ordinal.value < cutoffOrdinal.value) LegacyFormat
    else MerklePatriciaFormat
}
```

## GlobalSnapshotStateProof Structure

```scala
case class GlobalSnapshotStateProof(
  // Legacy fields (populated in legacy mode)
  lastStateChannelSnapshotHashes: Hash,
  lastTxRefs: Hash,
  balances: Hash,
  lastCurrencySnapshots: Option[MerkleRoot],
  activeAllowSpends: Option[Hash],
  // ... other legacy hashes
  
  // MPT field (populated in MPT mode)
  mptRoot: Option[Hash]  // Single root for all state
)
```

## MptStore Integration

The `MptStore` provides a typed interface over the producer:

```mermaid
classDiagram
    class MptStore~F, K~ {
        +get(key: K): F[Option[A]]
        +getMany(keys: List[K]): F[Map[K, A]]
        +sync(entries: Map[K, Json], ordinal): F[Unit]
        +syncFull(entries: Map[K, Json], ordinal): F[Unit]
        +remove(keys: List[K]): F[Unit]
        +build: F[MerklePatriciaTrie]
    }
    
    class GlobalStateKey {
        +networkNamespace
        +fieldId
        +contractNamespace
        +userNamespace
    }
    
    MptStore --> GlobalStateKey : uses as K
```

### Typed Accessors

```scala
// From GlobalStateConverter.syntax._
mptStore.getBalance(address)           // F[Option[Balance]]
mptStore.getTxRef(address)             // F[Option[TransactionReference]]
mptStore.getStateChannelHash(addr)     // F[Option[Hash]]
mptStore.getDelegatedStakes(address)   // F[Option[SortedSet[DelegatedStakeRecord]]]
mptStore.getCurrencySnapshot(addr)     // F[Option[Signed[CurrencySnapshot]]]
```

## Producer Types in Context

### Stateless Producer

Used for one-shot trie creation (validation, comparison):

```scala
// Create trie from snapshot info
val trie = info.allStateEntries[F].flatMap { entries =>
  MerklePatriciaTrie.makeParallel[F, Json](entries.toHexMap)
}
```

### InMemory Producer

Used for L0 node state tracking:

```scala
val producer: F[StatefulMerklePatriciaProducer[F]] = 
  MerklePatriciaProducer.inMemory[F](initialState)

// Incremental updates
producer.insert(newData)
producer.remove(keysToRemove)
val trie = producer.build
```

### FileSystem Producer

Used for persistent state with ordinal tracking:

```scala
val producer: F[StatefulWithPersistenceMerklePatriciaProducer[F]] = 
  FileSystemMerklePatriciaProducer.make[F](basePath)

// Persist at ordinal
producer.persist(ordinal)

// Load from ordinal
producer.load(ordinal)

// Cleanup old states
producer.applyCutoff(keepAboveOrdinal)
```

## Snapshot Acceptance Flow

```mermaid
sequenceDiagram
    participant P as Peer
    participant V as Validator
    participant M as MptStore
    participant A as AcceptanceManager
    
    P->>V: Received Snapshot
    V->>V: Validate signatures
    V->>M: Sync state changes
    M->>M: Update trie
    M->>V: New root hash
    V->>V: Compare with snapshot.stateProof.mptRoot
    
    alt Root matches
        V->>A: Accept snapshot
    else Root mismatch
        V->>A: Reject snapshot
    end
```

## Proof Validation in Consensus

Light clients can verify state claims:

```scala
def verifyBalanceClaim(
  address: Address,
  claimedBalance: Balance,
  proof: MerklePatriciaInclusionProof,
  snapshotRoot: Hash
): F[Boolean] = {
  val verifier = MerklePatriciaInclusionVerifier.make[F](snapshotRoot)
  verifier.confirm(proof).map(_.isRight)
}
```

## Key Namespacing Strategy

State is partitioned for efficient querying:

```
Hypergraph State (global):
  00 + fieldId + 00 + userHash
  Examples:
    - Balance: 00 00000002 00 01<addressHash>
    - TxRef:   00 00000001 00 01<addressHash>

Metagraph State (per-metagraph):
  01<metagraphHash> + fieldId + 00 + 00
  Examples:
    - Currency snapshot: 01<mgHash> 00000005 00 00
    - State channel hash: 01<mgHash> 00000000 00 00

Contract State (per-contract per-user):
  00 + fieldId + 01<contractHash> + 01<userHash>
  Examples:
    - AllowSpend: 00 00000007 01<contractHash> 01<userHash>
```

## Migration from Legacy

### Timeline

1. **TestNet** (2026-02-06): MPT activated, dual-mode testing
2. **IntegrationNet** (this release): Transition ordinal announced, developers update nodes
3. **MainNet** (TBD): Full network migration after IntegrationNet validation

### Dual-Mode Operation

During migration, both formats are supported:

```scala
def stateProof[F[_]: Async](ordinal: SnapshotOrdinal)(
  implicit selector: StateProofSelector
): F[GlobalSnapshotStateProof] =
  selector.select(ordinal) match {
    case LegacyFormat =>
      // Compute individual field hashes
      computeLegacyProof[F]()
    case MerklePatriciaFormat =>
      // Build MPT and use root
      allStateEntries[F].flatMap { entries =>
        buildMpt(entries).map {
          case Right(trie) => GlobalSnapshotStateProof(..., mptRoot = Some(trie.rootHash))
          case Left(error) => throw new RuntimeException(s"MPT build failed: $error")
        }
      }
  }
```

### Cutoff Ordinal

The network coordinates on a cutoff ordinal (TBD for IntegrationNet):

1. Before cutoff: Legacy proofs required
2. At/after cutoff: MPT proofs required
3. Validators reject wrong format for ordinal

> **Note:** The cutoff ordinal will be set after this release is deployed to allow metagraph developers time to update. Monitor official channels for the announcement.

## Error Handling

```scala
sealed trait MerklePatriciaError

case class InvalidData(message: String)     // Bad input data
case class OperationError(message: String)  // Insert/remove failure

// In state proof context
globalSnapshotInfo.stateProof(producer, ordinal).flatMap {
  case Left(err: MerklePatriciaError) => 
    // Handle MPT construction failure
  case Right(trie) =>
    // Use trie root
}
```

## Performance Tuning

### Batch Size

Large state updates are batched:

```scala
private val BatchSize = 5000

entries.grouped(BatchSize).traverse { batch =>
  store.sync(batch, ordinal) >> Async[F].cede
}
```

### Parallel Construction

For large initial state:

```scala
MerklePatriciaTrie.makeParallel[F, Json](data)
// Uses ParallelMerklePatriciaProducer internally
```

## Related Documentation

- [Architecture Overview](./architecture.md) - High-level design
- [API Reference](./api-reference.md) - Detailed API documentation
