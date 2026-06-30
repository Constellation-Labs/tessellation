# MPT API Reference

Complete API reference for Tessellation's Merkle Patricia Trie implementation.

## Package Structure

```
io.constellationnetwork.security.mpt
├── MerklePatriciaTrie          # Main trie type
├── MerklePatriciaNode          # Node ADT (Leaf, Branch, Extension)
├── MerklePatriciaCommitment    # Commitment structures for hashing
├── MptRoot                     # Type-safe root hash wrapper
├── Nibble                      # 4-bit value (0-15)
├── CompactNibblePath           # Memory-efficient nibble sequence
├── producer/
│   ├── MerklePatriciaProducer           # Stateless producer trait
│   ├── StatefulMerklePatriciaProducer   # Stateful producer trait
│   ├── StatelessMerklePatriciaProducer  # One-shot implementation
│   ├── ParallelMerklePatriciaProducer   # Parallel construction
│   ├── InMemoryMerklePatriciaProducer   # In-memory stateful
│   └── FileSystemMerklePatriciaProducer # Persistent stateful
├── prover/
│   ├── MerklePatriciaSingleInclusionProver
│   ├── MerklePatriciaBatchInclusionProver
│   ├── MerklePatriciaRangeProver
│   ├── MerklePatriciaPrefixProver
│   └── attestation/
│       ├── MerklePatriciaInclusionProof
│       ├── MerklePatriciaBatchInclusionProof
│       └── MerklePatriciaRangeProof
├── verifier/
│   ├── MerklePatriciaInclusionVerifier
│   ├── MerklePatriciaBatchInclusionVerifier
│   └── MerklePatriciaRangeVerifier
└── storages/
    └── MptStateStorage

io.constellationnetwork.schema.mpt
├── GlobalStateKey              # Structured key type
├── GlobalStateFieldId          # State field enumeration
├── PartitionNamespace          # Key namespace types
├── GlobalStateConverter        # State → key-value conversion
├── MptStore                    # Typed MPT storage interface (trait)
└── MptStoreSavepoint           # Captured store state for rollback
```

## Core Types

### MerklePatriciaTrie

```scala
final case class MerklePatriciaTrie(rootNode: MerklePatriciaNode) {
  def rootHash: MptRoot  // O(1) - pre-computed
}

object MerklePatriciaTrie {
  // Create from data (stateless)
  def make[F[_]: Hasher: Async, A: Encoder](
    data: Map[Hex, A]
  ): F[MerklePatriciaTrie]
  
  // Create with parallel construction
  def makeParallel[F[_]: Hasher: Async: Parallel: JsonSerializer, A: Encoder](
    data: Map[Hex, A]
  ): F[MerklePatriciaTrie]
  
  // Collect all leaf nodes
  def collectLeafNodes(trie: MerklePatriciaTrie): List[MerklePatriciaNode.Leaf]
  
  // Collect leaves with their full paths
  def collectLeafNodesWithPaths(trie: MerklePatriciaTrie): List[(Hex, MerklePatriciaNode.Leaf)]
}
```

### MptRoot

```scala
case class MptRoot(value: Hash) extends AnyVal
```

### MerklePatriciaNode

```scala
sealed trait MerklePatriciaNode {
  def digest: Hash
}

object MerklePatriciaNode {
  case class Leaf(
    remainingCompact: CompactNibblePath,
    dataDigest: Hash,
    digest: Hash
  ) extends MerklePatriciaNode {
    def remaining: Seq[Nibble]           // Compatibility accessor
    def remainingPath: CompactNibblePath // Efficient accessor
  }
  
  object Leaf {
    def apply[F[_]: Sync: Hasher](remaining: Seq[Nibble], dataDigest: Hash): F[Leaf]
    def fromCompact[F[_]: Sync: Hasher](remaining: CompactNibblePath, dataDigest: Hash): F[Leaf]
  }
  
  case class Branch(
    pathsInternal: Map[Byte, MerklePatriciaNode],
    digest: Hash
  ) extends MerklePatriciaNode {
    def paths: Map[Nibble, MerklePatriciaNode]  // Compatibility accessor
    def internalPaths: Map[Byte, MerklePatriciaNode]  // Efficient accessor
    def getChild(nibbleValue: Byte): Option[MerklePatriciaNode]
    def childCount: Int
  }
  
  object Branch {
    def apply[F[_]: Sync: Hasher](paths: Map[Nibble, MerklePatriciaNode]): F[Branch]
    def fromByteKeys[F[_]: Sync: Hasher](paths: Map[Byte, MerklePatriciaNode]): F[Branch]
    def empty[F[_]: Sync: Hasher]: F[Branch]
  }
  
  case class Extension(
    sharedCompact: CompactNibblePath,
    child: Branch,
    digest: Hash
  ) extends MerklePatriciaNode {
    def shared: Seq[Nibble]           // Compatibility accessor
    def sharedPath: CompactNibblePath // Efficient accessor
  }
  
  object Extension {
    def apply[F[_]: Sync: Hasher](shared: Seq[Nibble], child: Branch): F[Extension]
    def fromCompact[F[_]: Sync: Hasher](shared: CompactNibblePath, child: Branch): F[Extension]
  }
}
```

### Nibble

```scala
class Nibble(val value: Byte) extends AnyVal  // value ∈ [0, 15]

object Nibble {
  val empty: Nibble
  
  def apply(hex: Hex): Seq[Nibble]
  def apply(bytes: Array[Byte]): Seq[Nibble]
  def apply(byte: Byte): Seq[Nibble]  // Returns 2 nibbles
  
  def fromHexString(hexString: String): Either[InvalidNibble, Seq[Nibble]]
  def unsafe(byte: Byte): Nibble      // Asserts byte ∈ [0, 15]
  def unsafe(char: Char): Nibble
  def validated(byte: Byte): Validated[InvalidNibble, Nibble]
  
  def toBytes(nibbles: Seq[Nibble]): Array[Byte]
  def toHex(nibbles: Seq[Nibble]): Hex
  def commonPrefix(a: Seq[Nibble], b: Seq[Nibble]): Seq[Nibble]
}
```

### CompactNibblePath

```scala
final class CompactNibblePath(packed: Array[Byte], length: Int) {
  // Access
  def apply(index: Int): Byte         // Get nibble value at index
  def get(index: Int): Byte           // Alias for apply
  def getOrEmpty(index: Int): Byte    // Returns 0 if out of bounds
  def head: Byte
  def headOption: Option[Byte]
  
  // Properties
  def isEmpty: Boolean
  def nonEmpty: Boolean
  def length: Int
  
  // Slicing
  def tail: CompactNibblePath
  def drop(n: Int): CompactNibblePath
  def take(n: Int): CompactNibblePath
  def slice(from: Int, until: Int): CompactNibblePath
  
  // Operations
  def ++(other: CompactNibblePath): CompactNibblePath
  def prepend(nibbleValue: Byte): CompactNibblePath
  def startsWith(prefix: CompactNibblePath): Boolean
  def commonPrefixLength(other: CompactNibblePath): Int
  def commonPrefix(other: CompactNibblePath): CompactNibblePath
  
  // Conversion
  def toNibbleValues: Array[Byte]
  def toNibbleSeq: Seq[Nibble]
  def toHexString: String
  def toHex: Hex
  
  // Comparison
  def compare(other: CompactNibblePath): Int
  def equalsSeq(seq: Seq[Nibble]): Boolean
}

object CompactNibblePath {
  val empty: CompactNibblePath
  
  def fromNibbleValues(nibbles: Array[Byte]): CompactNibblePath
  def fromNibbleSeq(nibbles: Seq[Nibble]): CompactNibblePath
  def fromNibbles(nibbles: Seq[Nibble]): CompactNibblePath  // Alias
  def fromHexString(hex: String): CompactNibblePath
  def single(value: Byte): CompactNibblePath
  def fromNibble(nibble: Nibble): CompactNibblePath
}
```

## Producers

### MerklePatriciaProducer (Stateless)

```scala
trait MerklePatriciaProducer[F[_]] {
  def create[A: Encoder](data: Map[Hex, A]): F[MerklePatriciaTrie]
  
  def insert[A: Encoder](
    current: MerklePatriciaTrie,
    data: Map[Hex, A]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]]
  
  def remove(
    current: MerklePatriciaTrie,
    keys: List[Hex]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]]
  
  def getProver(trie: MerklePatriciaTrie): F[MerklePatriciaSingleInclusionProver[F]]
}

object MerklePatriciaProducer {
  def make[F[_]: Hasher: Async]: MerklePatriciaProducer[F]
  def stateless[F[_]: Hasher: Async]: MerklePatriciaProducer[F]
  def parallel[F[_]: Hasher: Async: Parallel: JsonSerializer]: MerklePatriciaProducer[F]
  
  def inMemory[F[_]: Async: Hasher: Parallel: JsonSerializer](
    initial: Map[Hex, Array[Byte]] = Map.empty
  ): F[StatefulMerklePatriciaProducer[F]]
}
```

### StatefulMerklePatriciaProducer

Source: `MerklePatriciaProducer.scala:41-75`.

```scala
trait StatefulMerklePatriciaProducer[F[_]] {
  def entries: F[Map[Hex, Array[Byte]]]
  def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]]

  // Build the trie and cache its root hash under `ordinal` for later retrieval
  def buildForOrdinal(ordinal: SnapshotOrdinal): F[Either[MerklePatriciaError, MerklePatriciaTrie]]
  // Cached historical root for an ordinal (None if too old or never built)
  def getRootHashForOrdinal(ordinal: SnapshotOrdinal): F[Option[MptRoot]]
  // Last-built root without rebuilding (None if never built)
  def getCurrentRootHash: F[Option[MptRoot]]
  // Ordinal of the most recent buildForOrdinal (None if never built)
  def getLastBuiltOrdinal: F[Option[SnapshotOrdinal]]

  def insert[A: Encoder](data: Map[Hex, A]): F[Either[MerklePatriciaError, Unit]]
  def insertBytes(data: Map[Hex, Array[Byte]]): F[Either[MerklePatriciaError, Unit]]
  def update[A: Encoder](key: Hex, value: A): F[Either[MerklePatriciaError, Unit]]
  def remove(keys: List[Hex]): F[Either[MerklePatriciaError, Unit]]
  def clear: F[Unit]

  def getProver: F[MerklePatriciaSingleInclusionProver[F]]
  def buildHexMap(data: Map[GlobalStateKey, Json]): F[Map[Hex, Array[Byte]]]

  // Capture all internal state (entries, trie, pending changes, caches) for rollback
  def savepoint: F[ProducerSavepoint[F]]
}
```

### ProducerSavepoint

A captured snapshot of a `StatefulMerklePatriciaProducer`'s internal state. Used to undo mutations from a failed artifact validation (for example a stateProof divergence). Source: `MerklePatriciaProducer.scala:34-39`.

```scala
trait ProducerSavepoint[F[_]] {
  def restore: F[Unit]
}
```

### StatefulWithPersistenceMerklePatriciaProducer

```scala
trait StatefulWithPersistenceMerklePatriciaProducer[F[_]] 
    extends StatefulMerklePatriciaProducer[F] {
  def persist(ordinal: SnapshotOrdinal): F[Unit]
  def load(ordinal: SnapshotOrdinal): F[Boolean]
  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit]
  def listStoredOrdinals: F[Stream[F, SnapshotOrdinal]]
  def applyCutoff(ordinal: SnapshotOrdinal): F[Unit]
}
```

## Provers

### MerklePatriciaSingleInclusionProver

```scala
trait MerklePatriciaSingleInclusionProver[F[_]] {
  def attestPath(path: Hex): F[Either[MerklePatriciaProofError, MerklePatriciaInclusionProof]]
}

object MerklePatriciaSingleInclusionProver {
  def make[F[_]: Async: Hasher](trie: MerklePatriciaTrie): MerklePatriciaSingleInclusionProver[F]
  
  object syntax {
    implicit class MerklePatriciaPathOps(path: Hex) {
      def attestInclusion[F[_]](implicit P: MerklePatriciaSingleInclusionProver[F]): F[Either[...]]
    }
  }
}
```

### MerklePatriciaBatchInclusionProver

```scala
trait MerklePatriciaBatchInclusionProver[F[_]] {
  def attestPaths(paths: List[Hex]): F[Either[MerklePatriciaProofError, MerklePatriciaBatchInclusionProof]]
}

object MerklePatriciaBatchInclusionProver {
  def make[F[_]: Async: Hasher](trie: MerklePatriciaTrie): MerklePatriciaBatchInclusionProver[F]
  
  object syntax {
    implicit class MerklePatriciaPathListOps(paths: List[Hex]) {
      def attestBatchInclusion[F[_]](implicit P: MerklePatriciaBatchInclusionProver[F]): F[Either[...]]
    }
  }
}
```

### MerklePatriciaRangeProver

```scala
trait MerklePatriciaRangeProver[F[_]] {
  def attestRange(startPath: Hex, endPath: Hex): F[Either[MerklePatriciaProofError, MerklePatriciaRangeProof]]
}

object MerklePatriciaRangeProver {
  def make[F[_]: Async: Hasher](trie: MerklePatriciaTrie): MerklePatriciaRangeProver[F]
  
  object syntax {
    implicit class MerklePatriciaRangeOps(startPath: Hex) {
      def attestRangeInclusion[F[_]](endPath: Hex)(implicit P: MerklePatriciaRangeProver[F]): F[Either[...]]
    }
  }
}
```

### MerklePatriciaPrefixProver

```scala
trait MerklePatriciaPrefixProver[F[_]] {
  def attestPrefix(prefix: Hex): F[Either[MerklePatriciaProofError, MerklePatriciaBatchInclusionProof]]
}

object MerklePatriciaPrefixProver {
  def make[F[_]: Async: Hasher](trie: MerklePatriciaTrie): MerklePatriciaPrefixProver[F]
  
  object syntax {
    implicit class MerklePatriciaPrefixOps(prefix: Hex) {
      def attestPrefixInclusion[F[_]](implicit P: MerklePatriciaPrefixProver[F]): F[Either[...]]
    }
  }
}
```

## Proof Types

### MerklePatriciaInclusionProof

```scala
case class MerklePatriciaInclusionProof(
  path: Hex,
  witness: List[MerklePatriciaCommitment]
)
```

### MerklePatriciaBatchInclusionProof

```scala
case class MerklePatriciaBatchInclusionProof(
  paths: List[Hex],                        // Sorted
  witness: List[MerklePatriciaCommitment]  // Deduplicated
)
```

### MerklePatriciaRangeProof

```scala
case class MerklePatriciaRangeProof(
  startPath: Hex,
  endPath: Hex,
  inclusionProofs: List[MerklePatriciaInclusionProof],
  exclusionBoundaries: Option[RangeExclusionBoundaries]
)

case class RangeExclusionBoundaries(
  leftBoundary: Option[MerklePatriciaInclusionProof],
  rightBoundary: Option[MerklePatriciaInclusionProof]
)
```

## Verifiers

### MerklePatriciaInclusionVerifier

```scala
trait MerklePatriciaInclusionVerifier[F[_]] {
  def confirm(proof: MerklePatriciaInclusionProof): F[Either[MerklePatriciaVerificationError, Unit]]
}

object MerklePatriciaInclusionVerifier {
  def make[F[_]: Async: Hasher](root: Hash): MerklePatriciaInclusionVerifier[F]
  
  object syntax {
    implicit class MerklePatriciaProofOps(proof: MerklePatriciaInclusionProof) {
      def confirm[F[_]](implicit V: MerklePatriciaInclusionVerifier[F]): F[Either[...]]
    }
  }
}
```

## Error Types

### Producer Errors

```scala
sealed trait MerklePatriciaError extends Throwable
case class InvalidData(message: String) extends MerklePatriciaError
case class OperationError(message: String) extends MerklePatriciaError
```

### Prover Errors

```scala
sealed trait MerklePatriciaProofError extends Throwable
case class PathNotFound(path: String) extends MerklePatriciaProofError
case class InvalidNodeType(message: String) extends MerklePatriciaProofError
case class ProofGenerationError(message: String) extends MerklePatriciaProofError
```

### Verifier Errors

```scala
sealed trait MerklePatriciaVerificationError extends Throwable
case class InvalidWitness(message: String) extends MerklePatriciaVerificationError
case class InvalidPath(message: String) extends MerklePatriciaVerificationError
case class InvalidNodeCommitment(message: String) extends MerklePatriciaVerificationError
```

## State Key Types

### GlobalStateKey

```scala
case class GlobalStateKey(
  networkNamespace: PartitionNamespace,
  fieldId: GlobalStateFieldId,
  contractNamespace: PartitionNamespace,
  userNamespace: PartitionNamespace
)

object GlobalStateKey {
  def metagraph(addr: Address, fieldId: GlobalStateFieldId): GlobalStateKey
  def hypergraph(fieldId: GlobalStateFieldId, user: Address): GlobalStateKey
  def hypergraph(fieldId: GlobalStateFieldId, contract: Address, user: Address): GlobalStateKey
  def hypergraph(fieldId: GlobalStateFieldId, contract: Option[Address], user: Address): GlobalStateKey
  
  def toHex[F[_]: Sync: Hasher](key: GlobalStateKey): F[Hex]
}
```

### GlobalStateFieldId

```scala
sealed trait GlobalStateFieldId { def toInt: Int }

object GlobalStateFieldId {
  case object LastStateChannelSnapshotHashes extends GlobalStateFieldId  // 0
  case object LastTxRefs extends GlobalStateFieldId                       // 1
  case object Balances extends GlobalStateFieldId                         // 2
  case object LastCurrencySnapshots extends GlobalStateFieldId            // 3
  case object LastCurrencySnapshotsProofs extends GlobalStateFieldId      // 4
  case object LastIncrementalCurrencySnapshots extends GlobalStateFieldId // 5
  case object LastCurrencySnapshotInfo extends GlobalStateFieldId         // 6
  case object ActiveAllowSpends extends GlobalStateFieldId                // 7
  case object ActiveTokenLocks extends GlobalStateFieldId                 // 8
  case object TokenLockBalances extends GlobalStateFieldId                // 9
  case object LastAllowSpendRefs extends GlobalStateFieldId               // 10
  case object LastTokenLockRefs extends GlobalStateFieldId                // 11
  case object UpdateNodeParameters extends GlobalStateFieldId             // 12
  case object ActiveDelegatedStakes extends GlobalStateFieldId            // 13
  case object DelegatedStakesWithdrawals extends GlobalStateFieldId       // 14
  case object ActiveNodeCollaterals extends GlobalStateFieldId            // 15
  case object NodeCollateralWithdrawals extends GlobalStateFieldId        // 16
  case object PriceState extends GlobalStateFieldId                       // 17
  case object MetagraphSyncData extends GlobalStateFieldId                // 18
  
  def fromInt(i: Int): Option[GlobalStateFieldId]
}
```

### PartitionNamespace

```scala
sealed trait PartitionNamespace { def keyType: PartitionKeyType }

object PartitionNamespace {
  case object HypergraphNamespace extends PartitionNamespace
  case class MetagraphNamespace(address: Address) extends PartitionNamespace
  case class AddressNamespace(address: Address) extends PartitionNamespace
  case class HashNamespace(hash: Hash) extends PartitionNamespace
  case object EmptyNamespace extends PartitionNamespace
}
```

## GlobalStateConverter Syntax

```scala
import GlobalStateConverter.syntax._

// GlobalSnapshotInfo extensions
globalSnapshotInfo.allStateEntries[F]  // F[Map[GlobalStateKey, Json]]

// StateChangesAccumulator extensions
accumulator.toStateEntries[F]  // F[Map[GlobalStateKey, Json]]

// Build MPT from key-value pairs
kvPairsF.buildMpt  // F[MptRoot]

// MptStore extensions (syntax, not trait methods - see MptStore section below)
mptStore.getBalance(address)                     // F[Option[Balance]]
mptStore.getBalances(addresses)                  // F[Map[Address, Balance]]
mptStore.getTxRef(address)                       // F[Option[TransactionReference]]
mptStore.getStateChannelHash(metagraphAddr)      // F[Option[Hash]]
mptStore.getAllowSpendRef(address)               // F[Option[AllowSpendReference]]
mptStore.getActiveAllowSpends(metagraphId, addr) // F[Option[SortedSet[Signed[AllowSpend]]]]
mptStore.getTokenLockRef(address)                // F[Option[TokenLockReference]]
mptStore.getActiveTokenLocks(address)            // F[Option[SortedSet[Signed[TokenLock]]]]
mptStore.getDelegatedStakes(address)             // F[Option[SortedSet[DelegatedStakeRecord]]]
mptStore.getNodeCollaterals(address)             // F[Option[SortedSet[NodeCollateralRecord]]]
mptStore.getCurrencySnapshot(metagraphAddr)      // F[Option[Signed[CurrencySnapshot]]]
mptStore.getCurrencySnapshotInfo(metagraphAddr)  // F[Option[CurrencySnapshotInfo]]

mptStore.syncFromGlobalSnapshotInfo(info, ordinal)   // F[Unit]
mptStore.syncFromStateChanges(accumulator, ordinal)  // F[Unit]
```

## MptStore

`MptStore[F, K]` is the typed key-value facade over a `StatefulMerklePatriciaProducer`. Keys of type `K` are turned into trie paths via a caller-supplied `toHex: K => F[Hex]`, and values are JSON-serialized through `JsonSerializer`. The `getBalance`/`syncFromGlobalSnapshotInfo`/etc. members in the section above are syntax extensions layered on top of this trait, not members of it. Source: `MptStore.scala:26-48`.

```scala
trait MptStore[F[_], K] {
  // Reads
  def get[V: Decoder](key: K): F[Option[V]]
  def getMany[V: Decoder](keys: List[K]): F[Map[K, V]]
  def contains(key: K): F[Boolean]
  def isEmpty: F[Boolean]

  // Mutations
  def insert[V: Encoder](key: K, value: V): F[Unit]
  def insert[V: Encoder](entries: Map[K, V]): F[Unit]
  def remove(key: K): F[Unit]
  def remove(keys: List[K]): F[Unit]
  def update[V: Encoder](toUpsert: Map[K, V], toRemove: Set[K]): F[Unit]
  def clear: F[Unit]

  // Build and sync against a snapshot ordinal
  def build(ordinal: SnapshotOrdinal): F[Either[MerklePatriciaError, MerklePatriciaTrie]]
  def sync[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit]
  def syncFull[V: Encoder](newState: Map[K, V], ordinal: SnapshotOrdinal): F[Unit]
  def syncFullIfNeeded[V: Encoder](
    newState: => F[Map[K, V]],
    ordinal: SnapshotOrdinal,
    expectedRoot: Option[Hash] = None
  ): F[Unit]

  // Persistence and rollback
  def deleteAbove(ordinal: SnapshotOrdinal): F[Unit]
  def underlying: StatefulMerklePatriciaProducer[F]
  def savepoint: F[MptStoreSavepoint[F]]
}
```

### Method semantics

| Method | Behavior |
|--------|----------|
| `get` / `getMany` | Read the current in-memory entry set, deserialize matched values. `getMany([])` short-circuits to an empty map. |
| `insert` / `remove` / `update` | Mutate pending producer state. `update` removes then upserts. |
| `build(ordinal)` | Delegates to `underlying.buildForOrdinal(ordinal)`, caching the root hash under that ordinal. |
| `sync` | Incremental: applies `newState` as inserts, persists, builds, and records `ordinal` as last-synced. No-op on an empty map. |
| `syncFull` | Full reset: `clear`, then insert all of `newState`, persist, build, record `ordinal`. An empty `newState` clears the store and records `ordinal`. |
| `syncFullIfNeeded` | Ordinal-gated `syncFull`. `newState` is a thunk evaluated only when a sync is needed. Skips when already synced at `ordinal`; when `expectedRoot` is supplied it builds and compares the current root, forcing a full resync on mismatch (or on a `Left` build) to avoid emitting a divergent root. |
| `deleteAbove` | Drops persisted state above `ordinal` (only for persistence-backed producers; otherwise a no-op). |
| `savepoint` | Captures producer state plus the last-synced ordinal; `MptStoreSavepoint.restore` rolls both back. |

### Concurrency contract

The heavy mutation methods (`syncFull`, `sync`, `update`, `deleteAbove`) and the multi-`Ref` `savepoint` capture/restore are serialized through an internal `Semaphore` (`mutationLock`) so concurrent callers cannot tear the producer's internal state. `insert`/`remove`/`clear`/`build` are NOT lock-wrapped: they are invoked only from inside the locked outer methods, so wrapping them would deadlock. Source: `MptStore.scala:58-69`.

### MptStoreSavepoint

Captured snapshot of an `MptStore`'s internal state (producer state plus last-synced ordinal). Used to undo mutations from a failed artifact validation (for example a stateProof divergence). Source: `MptStore.scala:19-24`.

```scala
trait MptStoreSavepoint[F[_]] {
  def restore: F[Unit]
}
```

## Usage Examples

### Create Trie and Generate Proof

```scala
import io.constellationnetwork.security.mpt._
import io.constellationnetwork.security.mpt.prover._
import io.constellationnetwork.security.mpt.verifier._

for {
  // Create trie
  trie <- MerklePatriciaTrie.makeParallel[IO, String](Map(
    Hex("abc123") -> "value1",
    Hex("abc456") -> "value2",
    Hex("def789") -> "value3"
  ))
  
  // Generate proof
  prover = MerklePatriciaSingleInclusionProver.make[IO](trie)
  proof <- prover.attestPath(Hex("abc123"))
  
  // Verify proof
  verifier = MerklePatriciaInclusionVerifier.make[IO](trie.rootHash.value)
  result <- proof.traverse(verifier.confirm)
} yield result
```

### Incremental Updates

```scala
for {
  producer <- MerklePatriciaProducer.inMemory[IO]()
  
  // Insert data
  _ <- producer.insert(Map(
    Hex("key1") -> "value1",
    Hex("key2") -> "value2"
  ))
  
  // Build trie
  trie1 <- producer.build
  
  // Update
  _ <- producer.update(Hex("key1"), "updated")
  _ <- producer.remove(List(Hex("key2")))
  
  // Rebuild
  trie2 <- producer.build
} yield (trie1, trie2)
```

### Batch Proof with Deduplication

```scala
for {
  trie <- buildTrie(largeDataset)
  prover = MerklePatriciaBatchInclusionProver.make[IO](trie)
  
  // Prove multiple related keys
  proof <- prover.attestPaths(List(
    Hex("abc001"),
    Hex("abc002"),
    Hex("abc003")
  ))
  // Witness is deduplicated - shared path commitments appear once
} yield proof
```
