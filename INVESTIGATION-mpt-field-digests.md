# Investigation: MPT Field Digests

## Problem Statement

Currently, when constructing `GlobalSnapshotStateProof` using MPT format, all individual field hashes are set to `Hash.empty`:

```scala
GlobalSnapshotStateProof.apply(
  Hash.empty,  // lastStateChannelSnapshotHashesProof
  Hash.empty,  // lastTxRefsProof
  Hash.empty,  // balancesProof
  None,        // lastCurrencySnapshotsProof
  None,        // activeAllowSpends
  // ... all None ...
  Some(mptRoot.value)  // Only this is populated
)
```

This loses insight into what's changing in each field - the only observable change is the root hash.

## Goal

Extract per-field digests from the MPT to populate the individual proof fields, giving visibility into which state components changed.

## Key Structure

### GlobalStateFieldId (19 fields)
```scala
0  = LastStateChannelSnapshotHashes
1  = LastTxRefs
2  = Balances
3  = LastCurrencySnapshots
4  = LastCurrencySnapshotsProofs
5  = LastIncrementalCurrencySnapshots
6  = LastCurrencySnapshotInfo
7  = ActiveAllowSpends
8  = ActiveTokenLocks
9  = TokenLockBalances
10 = LastAllowSpendRefs
11 = LastTokenLockRefs
12 = UpdateNodeParameters
13 = ActiveDelegatedStakes
14 = DelegatedStakesWithdrawals
15 = ActiveNodeCollaterals
16 = NodeCollateralWithdrawals
17 = PriceState
18 = MetagraphSyncData
```

### Key Format
```
[networkNamespace][fieldId (8 hex)][contractNamespace][userNamespace]
```

For hypergraph data:
- Prefix: `00` (HypergraphNamespace) + `XXXXXXXX` (fieldId hex)
- Example: Balances (field 2) → prefix `0000000002`

## Approach

### Option 1: Subtrie Root Hash

For each field prefix, traverse the MPT and find the subtrie root:

```scala
def getSubtrieHash(trie: MerklePatriciaTrie, prefix: Hex): Option[Hash] = {
  // Traverse to prefix
  // Return digest of node at/after prefix
}
```

**Pros:**
- Single hash per field
- Efficient to compute during proof building

**Cons:**
- The trie structure doesn't guarantee a node exactly at the field boundary
- Extension nodes might span across the field prefix

### Option 2: Aggregate Hash of Field Entries

Collect all leaf digests under the prefix and hash them together:

```scala
def getFieldAggregateHash(trie: MerklePatriciaTrie, prefix: Hex): F[Hash] = {
  for {
    leaves <- collectLeavesUnderPrefix(trie.rootNode, prefix)
    digests = leaves.map(_.dataDigest).sorted
    aggregate <- Hasher[F].hash(digests.mkString)
  } yield aggregate
}
```

**Pros:**
- Deterministic regardless of trie structure
- Changes when any entry under the field changes

**Cons:**
- More expensive to compute
- Requires traversing all leaves under prefix

### Option 3: Committed Subtrie Hash

Store the subtrie root hash explicitly when building the trie:

```scala
// During MPT construction, track per-field roots
case class MptWithFieldRoots(
  trie: MerklePatriciaTrie,
  fieldRoots: Map[GlobalStateFieldId, Hash]
)
```

**Pros:**
- Most accurate representation
- Computed once during trie construction

**Cons:**
- Requires modifying the MPT producer
- More complex implementation

## Investigation Code

```scala
// File: modules/shared/src/main/scala/io/constellationnetwork/security/mpt/MptFieldDigests.scala

package io.constellationnetwork.security.mpt

import cats.effect.Sync
import cats.syntax.all._

import io.constellationnetwork.schema.mpt.GlobalStateFieldId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

object MptFieldDigests {

  /** Get the subtrie root hash for a given prefix.
    * 
    * Traverses the trie following the prefix nibbles. Returns the digest of:
    * - The first node that is fully contained within the prefix
    * - Or Hash.empty if no data exists under that prefix
    */
  def getSubtrieDigest[F[_]: Sync: Hasher](
    trie: MerklePatriciaTrie,
    prefix: Hex
  ): F[Hash] = {
    val prefixNibbles = Nibble(prefix)
    
    def traverse(node: MerklePatriciaNode, remainingPrefix: Seq[Nibble]): Hash = {
      if (remainingPrefix.isEmpty) {
        // We've consumed the entire prefix - return this node's digest
        node.digest
      } else {
        node match {
          case _: MerklePatriciaNode.Leaf =>
            // Leaf before consuming prefix means no subtrie at exact prefix
            // But the leaf IS under this prefix, so return its digest
            node.digest
            
          case ext: MerklePatriciaNode.Extension =>
            val shared = ext.shared
            if (remainingPrefix.startsWith(shared)) {
              // Extension is within our prefix path
              traverse(ext.child, remainingPrefix.drop(shared.length))
            } else if (shared.startsWith(remainingPrefix)) {
              // Extension extends beyond our prefix - return child's digest
              ext.child.digest
            } else {
              // Divergent path - no data under this prefix
              Hash.empty
            }
            
          case branch: MerklePatriciaNode.Branch =>
            val nextNibble = remainingPrefix.head
            branch.paths.get(nextNibble) match {
              case Some(child) => traverse(child, remainingPrefix.tail)
              case None => Hash.empty // No data under this prefix
            }
        }
      }
    }
    
    traverse(trie.rootNode, prefixNibbles).pure[F]
  }
  
  /** Build field prefix for hypergraph state */
  def hypergraphFieldPrefix(fieldId: GlobalStateFieldId): Hex = {
    // HypergraphNamespace = 0x00, then fieldId as 8 hex chars
    Hex(f"00${fieldId.toInt}%08x")
  }
  
  /** Extract all field digests from a trie */
  def extractAllFieldDigests[F[_]: Sync: Hasher](
    trie: MerklePatriciaTrie
  ): F[Map[GlobalStateFieldId, Hash]] = {
    val allFieldIds = (0 to 18).flatMap(GlobalStateFieldId.fromInt)
    
    allFieldIds.toList.traverse { fieldId =>
      val prefix = hypergraphFieldPrefix(fieldId)
      getSubtrieDigest[F](trie, prefix).map(fieldId -> _)
    }.map(_.toMap)
  }
}
```

## Next Steps

1. **Verify prefix structure** - Confirm the key serialization matches expectations
2. **Test with real data** - Run against a populated trie to see actual subtrie hashes
3. **Evaluate options** - Determine which approach gives the best trade-off
4. **Integrate** - Modify `mptStateProof` to populate field hashes

## Questions for James

1. Is Option 1 (subtrie root at prefix boundary) sufficient, or do we need exact aggregation of all field entries?
2. Should we track per-metagraph field digests as well, or just hypergraph-level?
3. What's the acceptable performance impact for proof generation?
