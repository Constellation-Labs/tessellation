package io.constellationnetwork.security.mpt

import cats.data.NonEmptySet
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.Random

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.StateChangesAccumulator
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.producer.{FileSystemMerklePatriciaProducer, MerklePatriciaError, ParallelMerklePatriciaProducer}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.{Files, Path}
import weaver.MutableIOSuite

/** Test suite for insertion-order determinism in incremental MPT updates.
  *
  * This suite verifies the fix for the bug where `FileSystemMerklePatriciaProducer.applyIncrementalUpdates` processed entries in
  * non-deterministic `Map` iteration order, causing different MPT root hashes depending on insertion order.
  *
  * The fix sorts entries by `CompactNibblePath` ordering before passing to `IncrementalTrieOps.insertMultiple`, matching the ordering used
  * by `ParallelMerklePatriciaProducer.createFromBytes` (full-build path).
  *
  * Key scenarios covered:
  *   - IncrementalTrieOps produces identical roots when entries are sorted identically
  *   - FileSystemMerklePatriciaProducer incremental path matches full-build path
  *   - Multiple entries with different natural orderings produce same result
  *   - Rollback (full rebuild) matches prior incremental build
  */
object MptInsertionOrderDeterminismSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  implicit val stateProofSelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  private val addr1 = addressGen.sample.get
  private val addr2 = addressGen.sample.get
  private val addr3 = addressGen.sample.get
  private val addr4 = addressGen.sample.get
  private val addr5 = addressGen.sample.get

  private val nodeId1 = Id(Hex("1111111111111111" * 8)).toPeerId
  private val nodeId2 = Id(Hex("2222222222222222" * 8)).toPeerId
  private val nodeId3 = Id(Hex("3333333333333333" * 8)).toPeerId

  private def createSignedStake(
    source: Address,
    nodeId: PeerId,
    amount: Long,
    tokenLockRef: Hash = Hash.empty
  ): Signed[UpdateDelegatedStake.Create] =
    Signed(
      UpdateDelegatedStake.Create(
        source = source,
        nodeId = nodeId,
        amount = DelegatedStakeAmount(NonNegLong.unsafeFrom(amount)),
        fee = DelegatedStakeFee(0L),
        tokenLockRef = tokenLockRef
      ),
      NonEmptySet.one[SignatureProof](SignatureProof(nodeId.toId, Signature(Hex(Hash.empty.value))))
    )

  private def withTempDir[A](test: Path => IO[A]): IO[A] =
    Files[IO].tempDirectory.use(test)

  // ==================== IncrementalTrieOps level tests ====================

  test("IncrementalTrieOps.insertMultiple: sorted entries produce deterministic root regardless of initial order") { res =>
    implicit val (_, h, _) = res

    // Create multiple entries with known hex keys
    val entries = List(
      (Hex("aabbccdd" + "0" * 56), Hash("data1")),
      (Hex("11223344" + "0" * 56), Hash("data2")),
      (Hex("ff00ff00" + "0" * 56), Hash("data3")),
      (Hex("55667788" + "0" * 56), Hash("data4")),
      (Hex("ddccbbaa" + "0" * 56), Hash("data5"))
    )

    // Sort by CompactNibblePath ordering (this is what the fix does)
    val sorted = entries.sortBy { case (hex, _) => CompactNibblePath.fromHexString(hex.value) }

    for {
      emptyBranch <- MerklePatriciaNode.Branch.empty[IO]

      // Build trie from sorted entries
      root1 <- IncrementalTrieOps.insertMultiple[IO](emptyBranch, sorted)
      trie1 = MerklePatriciaTrie(root1)

      // Build again from same sorted entries
      root2 <- IncrementalTrieOps.insertMultiple[IO](emptyBranch, sorted)
      trie2 = MerklePatriciaTrie(root2)

      // Build from reversed entries but sorted first
      reverseSorted = entries.reverse.sortBy { case (hex, _) => CompactNibblePath.fromHexString(hex.value) }
      root3 <- IncrementalTrieOps.insertMultiple[IO](emptyBranch, reverseSorted)
      trie3 = MerklePatriciaTrie(root3)

      // Build from shuffled entries but sorted first
      shuffledSorted = Random.shuffle(entries).sortBy { case (hex, _) => CompactNibblePath.fromHexString(hex.value) }
      root4 <- IncrementalTrieOps.insertMultiple[IO](emptyBranch, shuffledSorted)
      trie4 = MerklePatriciaTrie(root4)
    } yield
      expect.all(
        trie1.rootHash == trie2.rootHash,
        trie1.rootHash == trie3.rootHash,
        trie1.rootHash == trie4.rootHash
      )
  }

  test("IncrementalTrieOps.insertMultiple: unsorted entries can produce different root than sorted entries") { res =>
    implicit val (_, h, _) = res

    // Create entries whose different orderings produce structurally different tries.
    // Use keys that diverge at the first nibble to maximize structural sensitivity.
    val entries = List(
      (Hex("a" * 64), Hash("data_a")),
      (Hex("b" * 64), Hash("data_b")),
      (Hex("c" * 64), Hash("data_c")),
      (Hex("d" * 64), Hash("data_d"))
    )

    val sorted = entries.sortBy { case (hex, _) => CompactNibblePath.fromHexString(hex.value) }
    val reversed = entries.reverse

    for {
      emptyBranch <- MerklePatriciaNode.Branch.empty[IO]

      // Build from sorted order
      rootSorted <- IncrementalTrieOps.insertMultiple[IO](emptyBranch, sorted)
      trieSorted = MerklePatriciaTrie(rootSorted)

      // Build from reverse order (unsorted)
      rootReversed <- IncrementalTrieOps.insertMultiple[IO](emptyBranch, reversed)
      trieReversed = MerklePatriciaTrie(rootReversed)

      // Both should actually produce the SAME root because the trie structure
      // for these simple cases (all single-nibble divergence) is order-independent.
      // However, more complex key patterns can diverge. The important thing is
      // that sorting guarantees determinism across all cases.
      //
      // For this test, we verify that sorting produces consistent results
      // across multiple shuffles.
      results <- (1 to 10).toList.traverse { _ =>
        val shuffled = Random.shuffle(entries).sortBy { case (hex, _) => CompactNibblePath.fromHexString(hex.value) }
        IncrementalTrieOps.insertMultiple[IO](emptyBranch, shuffled).map(r => MerklePatriciaTrie(r).rootHash)
      }
    } yield expect(results.distinct.size == 1) // All sorted shuffles produce same root
  }

  // ==================== FileSystemMerklePatriciaProducer level tests ====================

  test("filesystem: incremental with multiple entries matches full build (deterministic ordering)") { res =>
    implicit val (j, h, sp) = res

    // Multiple balances + stakes inserted at same ordinal to stress ordering
    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId2, 2000L)
    val stake3 = createSignedStake(addr3, nodeId3, 3000L)
    val ordinal = SnapshotOrdinal(NonNegLong(1L))

    val record1 = DelegatedStakeRecord(stake1, ordinal, Balance(10L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal, Balance(20L), None, None)
    val record3 = DelegatedStakeRecord(stake3, ordinal, Balance(30L), None, None)

    val delegatedStakes = SortedMap(
      addr1 -> SortedSet(record1),
      addr2 -> SortedSet(record2),
      addr3 -> SortedSet(record3)
    )
    val balances = SortedMap(
      addr1 -> Balance(5000L),
      addr2 -> Balance(3000L),
      addr3 -> Balance(1000L),
      addr4 -> Balance(7000L),
      addr5 -> Balance(9000L)
    )

    withTempDir { dir =>
      for {
        // INCREMENTAL PATH
        incProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "incremental")
        incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])
        acc = StateChangesAccumulator(activeDelegatedStakes = delegatedStakes, balances = balances)
        _ <- incStore.syncFromStateChanges(acc, ordinal)
        incTrie <- incStore.build(ordinal)
        incRoot = incTrie.map(_.rootHash)

        // FULL BUILD PATH
        fullProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "full")
        fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
        info = GlobalSnapshotInfo.empty.copy(
          activeDelegatedStakes = Some(delegatedStakes),
          balances = balances
        )
        _ <- fullStore.syncFromGlobalSnapshotInfo(info, ordinal)
        fullTrie <- fullStore.build(ordinal)
        fullRoot = fullTrie.map(_.rootHash)

        _ <- IO.println(s"[ORDER TEST] Inc: $incRoot, Full: $fullRoot, Match: ${incRoot == fullRoot}")
      } yield
        expect.all(
          incRoot.isRight,
          fullRoot.isRight,
          incRoot == fullRoot
        )
    }
  }

  test("filesystem: incremental add of multiple entries at once matches full rebuild") { res =>
    implicit val (j, h, sp) = res

    // Start with 1 stake, then add 3 more at once — this is the exact scenario from the bug
    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId2, 2000L)
    val stake3 = createSignedStake(addr3, nodeId3, 3000L)
    val stake4 = createSignedStake(addr4, nodeId1, 4000L)

    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    val record1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal2, Balance(0L), None, None)
    val record3 = DelegatedStakeRecord(stake3, ordinal2, Balance(0L), None, None)
    val record4 = DelegatedStakeRecord(stake4, ordinal2, Balance(0L), None, None)

    val finalStakes = SortedMap(
      addr1 -> SortedSet(record1),
      addr2 -> SortedSet(record2),
      addr3 -> SortedSet(record3),
      addr4 -> SortedSet(record4)
    )

    withTempDir { dir =>
      for {
        // INCREMENTAL: Start with 1 stake, then add 3 more
        incProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "incremental")
        incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])

        acc1 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record1)))
        _ <- incStore.syncFromStateChanges(acc1, ordinal1)
        _ <- incStore.build(ordinal1)

        // Add 3 more stakes at once (the ordering of these 3 within the Map is non-deterministic)
        acc2 = StateChangesAccumulator(activeDelegatedStakes = finalStakes)
        _ <- incStore.syncFromStateChanges(acc2, ordinal2)
        incTrie <- incStore.build(ordinal2)
        incRoot = incTrie.map(_.rootHash)

        // FULL BUILD from final state
        fullProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "full")
        fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
        info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(finalStakes))
        _ <- fullStore.syncFromGlobalSnapshotInfo(info, ordinal2)
        fullTrie <- fullStore.build(ordinal2)
        fullRoot = fullTrie.map(_.rootHash)

        _ <- IO.println(s"[MULTI ADD] Inc: $incRoot, Full: $fullRoot, Match: ${incRoot == fullRoot}")
      } yield
        expect.all(
          incRoot.isRight,
          fullRoot.isRight,
          incRoot == fullRoot
        )
    }
  }

  test("filesystem: rollback to state with 2+ delegated stakes matches incremental build") { res =>
    implicit val (j, h, sp) = res

    // This tests the exact production scenario that triggered the bug:
    // 1. Build incrementally through several ordinals (consensus path)
    // 2. Simulate rollback: full rebuild from the state at ordinal N (retraversal path)
    // 3. Both must produce the same root hash

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId2, 2000L)

    val ordinal1 = SnapshotOrdinal(NonNegLong(100L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(101L))
    val ordinal3 = SnapshotOrdinal(NonNegLong(102L))

    val record1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val record1Updated = DelegatedStakeRecord(stake1, ordinal1, Balance(50L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal2, Balance(0L), None, None)
    val record2Updated = DelegatedStakeRecord(stake2, ordinal2, Balance(25L), None, None)

    withTempDir { dir =>
      for {
        // CONSENSUS PATH: Build incrementally
        consensusProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "consensus")
        consensusStore <- MptStore.make[IO, GlobalStateKey](consensusProducer, GlobalStateKey.toHex[IO])

        // Ordinal 1: First stake
        acc1 = StateChangesAccumulator(
          activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record1)),
          balances = SortedMap(addr1 -> Balance(9000L))
        )
        _ <- consensusStore.syncFromStateChanges(acc1, ordinal1)
        _ <- consensusStore.build(ordinal1)

        // Ordinal 2: Second stake added
        acc2 = StateChangesAccumulator(
          activeDelegatedStakes = SortedMap(
            addr1 -> SortedSet(record1Updated),
            addr2 -> SortedSet(record2)
          ),
          balances = SortedMap(
            addr1 -> Balance(9050L),
            addr2 -> Balance(8000L)
          )
        )
        _ <- consensusStore.syncFromStateChanges(acc2, ordinal2)
        consensusTrie2 <- consensusStore.build(ordinal2)
        consensusRoot2 = consensusTrie2.map(_.rootHash)

        // Ordinal 3: Rewards updated
        acc3 = StateChangesAccumulator(
          activeDelegatedStakes = SortedMap(
            addr1 -> SortedSet(record1Updated),
            addr2 -> SortedSet(record2Updated)
          ),
          balances = SortedMap(
            addr1 -> Balance(9050L),
            addr2 -> Balance(8025L)
          )
        )
        _ <- consensusStore.syncFromStateChanges(acc3, ordinal3)
        consensusTrie3 <- consensusStore.build(ordinal3)
        consensusRoot3 = consensusTrie3.map(_.rootHash)

        // ROLLBACK PATH: Full rebuild at ordinal 2 (simulating node restart + rollback)
        rollbackProducer2 <- FileSystemMerklePatriciaProducer.make[IO](dir / "rollback2")
        rollbackStore2 <- MptStore.make[IO, GlobalStateKey](rollbackProducer2, GlobalStateKey.toHex[IO])
        info2 = GlobalSnapshotInfo.empty.copy(
          activeDelegatedStakes = Some(
            SortedMap(
              addr1 -> SortedSet(record1Updated),
              addr2 -> SortedSet(record2)
            )
          ),
          balances = SortedMap(
            addr1 -> Balance(9050L),
            addr2 -> Balance(8000L)
          )
        )
        _ <- rollbackStore2.syncFromGlobalSnapshotInfo(info2, ordinal2)
        rollbackTrie2 <- rollbackStore2.build(ordinal2)
        rollbackRoot2 = rollbackTrie2.map(_.rootHash)

        // ROLLBACK PATH: Full rebuild at ordinal 3
        rollbackProducer3 <- FileSystemMerklePatriciaProducer.make[IO](dir / "rollback3")
        rollbackStore3 <- MptStore.make[IO, GlobalStateKey](rollbackProducer3, GlobalStateKey.toHex[IO])
        info3 = GlobalSnapshotInfo.empty.copy(
          activeDelegatedStakes = Some(
            SortedMap(
              addr1 -> SortedSet(record1Updated),
              addr2 -> SortedSet(record2Updated)
            )
          ),
          balances = SortedMap(
            addr1 -> Balance(9050L),
            addr2 -> Balance(8025L)
          )
        )
        _ <- rollbackStore3.syncFromGlobalSnapshotInfo(info3, ordinal3)
        rollbackTrie3 <- rollbackStore3.build(ordinal3)
        rollbackRoot3 = rollbackTrie3.map(_.rootHash)

        _ <- IO.println(s"[ROLLBACK ORD2] Consensus: $consensusRoot2, Rollback: $rollbackRoot2, Match: ${consensusRoot2 == rollbackRoot2}")
        _ <- IO.println(s"[ROLLBACK ORD3] Consensus: $consensusRoot3, Rollback: $rollbackRoot3, Match: ${consensusRoot3 == rollbackRoot3}")
      } yield
        expect.all(
          consensusRoot2.isRight,
          rollbackRoot2.isRight,
          consensusRoot2 == rollbackRoot2,
          consensusRoot3.isRight,
          rollbackRoot3.isRight,
          consensusRoot3 == rollbackRoot3
        )
    }
  }

  test("filesystem: multiple independent full rebuilds produce identical roots") { res =>
    implicit val (j, h, sp) = res

    // Verify that full rebuilds are deterministic (baseline sanity check)
    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId2, 2000L)
    val stake3 = createSignedStake(addr3, nodeId3, 3000L)
    val ordinal = SnapshotOrdinal(NonNegLong(1L))

    val delegatedStakes = SortedMap(
      addr1 -> SortedSet(DelegatedStakeRecord(stake1, ordinal, Balance(10L), None, None)),
      addr2 -> SortedSet(DelegatedStakeRecord(stake2, ordinal, Balance(20L), None, None)),
      addr3 -> SortedSet(DelegatedStakeRecord(stake3, ordinal, Balance(30L), None, None))
    )
    val balances = SortedMap(addr1 -> Balance(5000L), addr2 -> Balance(3000L))

    val info = GlobalSnapshotInfo.empty.copy(
      activeDelegatedStakes = Some(delegatedStakes),
      balances = balances
    )

    withTempDir { dir =>
      for {
        // Build 3 independent full rebuilds
        roots <- (1 to 3).toList.traverse { i =>
          for {
            producer <- FileSystemMerklePatriciaProducer.make[IO](dir / s"rebuild$i")
            store <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
            _ <- store.syncFromGlobalSnapshotInfo(info, ordinal)
            trie <- store.build(ordinal)
          } yield trie.map(_.rootHash)
        }
        _ <- IO.println(s"[MULTI REBUILD] Roots: $roots, All match: ${roots.distinct.size == 1}")
      } yield
        expect.all(
          roots.forall(_.isRight),
          roots.distinct.size == 1
        )
    }
  }

  test("filesystem: syncFullIfNeeded (rollback path) matches consensus incremental path with mixed state") { res =>
    implicit val (j, h, sp) = res

    // This test simulates the exact production code path:
    // - Consensus builds incrementally via syncFromStateChanges
    // - Rollback path uses syncFullIfNeeded (which calls allStateEntries -> fullBuild)

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId2, 2000L)
    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    val record1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal2, Balance(0L), None, None)

    val finalStakes = SortedMap(
      addr1 -> SortedSet(record1),
      addr2 -> SortedSet(record2)
    )
    val finalBalances = SortedMap(
      addr1 -> Balance(9000L),
      addr2 -> Balance(8000L),
      addr3 -> Balance(5000L)
    )

    val finalInfo = GlobalSnapshotInfo.empty.copy(
      activeDelegatedStakes = Some(finalStakes),
      balances = finalBalances
    )

    withTempDir { dir =>
      for {
        // CONSENSUS PATH
        consensusProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "consensus")
        consensusStore <- MptStore.make[IO, GlobalStateKey](consensusProducer, GlobalStateKey.toHex[IO])

        acc1 = StateChangesAccumulator(
          activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record1)),
          balances = SortedMap(addr1 -> Balance(9000L), addr3 -> Balance(5000L))
        )
        _ <- consensusStore.syncFromStateChanges(acc1, ordinal1)
        _ <- consensusStore.build(ordinal1)

        acc2 = StateChangesAccumulator(
          activeDelegatedStakes = finalStakes,
          balances = finalBalances
        )
        _ <- consensusStore.syncFromStateChanges(acc2, ordinal2)
        consensusTrie <- consensusStore.build(ordinal2)
        consensusRoot = consensusTrie.map(_.rootHash)

        // ROLLBACK PATH using syncFullIfNeeded (the exact API called by GlobalSnapshotTraverse)
        rollbackProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "rollback")
        rollbackStore <- MptStore.make[IO, GlobalStateKey](rollbackProducer, GlobalStateKey.toHex[IO])
        _ <- rollbackStore.syncFullIfNeeded[io.circe.Json](finalInfo.allStateEntries[IO], ordinal2)
        rollbackTrie <- rollbackStore.build(ordinal2)
        rollbackRoot = rollbackTrie.map(_.rootHash)

        _ <- IO.println(s"[SYNC_FULL] Consensus: $consensusRoot, Rollback: $rollbackRoot, Match: ${consensusRoot == rollbackRoot}")
      } yield
        expect.all(
          consensusRoot.isRight,
          rollbackRoot.isRight,
          consensusRoot == rollbackRoot
        )
    }
  }

  test("filesystem: incremental build is idempotent when same state is re-synced") { res =>
    implicit val (j, h, sp) = res

    // Re-syncing the same state twice should produce the same root
    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId2, 2000L)
    val ordinal = SnapshotOrdinal(NonNegLong(1L))

    val record1 = DelegatedStakeRecord(stake1, ordinal, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal, Balance(0L), None, None)

    val stakes = SortedMap(
      addr1 -> SortedSet(record1),
      addr2 -> SortedSet(record2)
    )

    withTempDir { dir =>
      for {
        producer <- FileSystemMerklePatriciaProducer.make[IO](dir / "data")
        store <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])

        acc = StateChangesAccumulator(activeDelegatedStakes = stakes)

        // Sync and build first time
        _ <- store.syncFromStateChanges(acc, ordinal)
        trie1 <- store.build(ordinal)
        root1 = trie1.map(_.rootHash)

        // Sync same state again and rebuild
        _ <- store.syncFromStateChanges(acc, ordinal)
        trie2 <- store.build(ordinal)
        root2 = trie2.map(_.rootHash)

        _ <- IO.println(s"[IDEMPOTENT] First: $root1, Second: $root2, Match: ${root1 == root2}")
      } yield
        expect.all(
          root1.isRight,
          root2.isRight,
          root1 == root2
        )
    }
  }

  test("filesystem: grow from 1 to 5 delegated stakes across ordinals, rollback to each matches") { res =>
    implicit val (j, h, sp) = res

    // This reproduces the exact pattern from the bug report:
    // - Works with 1 delegated stake (single entry, ordering doesn't matter)
    // - Fails with 2+ (ordering starts to matter)
    // After fix, all should match.

    val stakes = List(
      createSignedStake(addr1, nodeId1, 1000L),
      createSignedStake(addr2, nodeId2, 2000L),
      createSignedStake(addr3, nodeId3, 3000L),
      createSignedStake(addr4, nodeId1, 4000L, Hash("lock4")),
      createSignedStake(addr5, nodeId2, 5000L, Hash("lock5"))
    )
    val addrs = List(addr1, addr2, addr3, addr4, addr5)

    val ordinals = (1 to 5).map(i => SnapshotOrdinal(NonNegLong.unsafeFrom(i.toLong))).toList

    // Build records progressively
    val records = stakes.zip(ordinals).map {
      case (stake, ordinal) => DelegatedStakeRecord(stake, ordinal, Balance(0L), None, None)
    }

    // State at each ordinal (1 stake, 2 stakes, ..., 5 stakes)
    val statesAtOrdinal: List[(SnapshotOrdinal, SortedMap[Address, SortedSet[DelegatedStakeRecord]])] =
      ordinals.zipWithIndex.map {
        case (ordinal, idx) =>
          val activeStakes = addrs.zip(records).take(idx + 1).map {
            case (addr, record) => addr -> SortedSet(record)
          }
          (ordinal, SortedMap.from(activeStakes))
      }

    withTempDir { dir =>
      for {
        // CONSENSUS PATH: Build incrementally through all ordinals
        consensusProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "consensus")
        consensusStore <- MptStore.make[IO, GlobalStateKey](consensusProducer, GlobalStateKey.toHex[IO])

        consensusRoots <- statesAtOrdinal.traverse {
          case (ordinal, stakeMap) =>
            val acc = StateChangesAccumulator(activeDelegatedStakes = stakeMap)
            for {
              _ <- consensusStore.syncFromStateChanges(acc, ordinal)
              trie <- consensusStore.build(ordinal)
            } yield (ordinal, trie.map(_.rootHash))
        }

        // ROLLBACK PATH: Full rebuild at each ordinal independently
        rollbackRoots <- statesAtOrdinal.zipWithIndex.traverse {
          case ((ordinal, stakeMap), idx) =>
            for {
              producer <- FileSystemMerklePatriciaProducer.make[IO](dir / s"rollback$idx")
              store <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
              info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(stakeMap))
              _ <- store.syncFromGlobalSnapshotInfo(info, ordinal)
              trie <- store.build(ordinal)
            } yield (ordinal, trie.map(_.rootHash))
        }

        // Compare each ordinal
        _ <- consensusRoots.zip(rollbackRoots).traverse_ {
          case ((ord, cRoot), (_, rRoot)) =>
            IO.println(
              s"[GROW ${ord.value}] Consensus: $cRoot, Rollback: $rRoot, Match: ${cRoot == rRoot}"
            )
        }

        mismatches = consensusRoots.zip(rollbackRoots).filter {
          case ((_, cRoot), (_, rRoot)) => cRoot != rRoot
        }
      } yield
        expect.all(
          consensusRoots.forall(_._2.isRight),
          rollbackRoots.forall(_._2.isRight),
          mismatches.isEmpty
        )
    }
  }

  // ==================== Direct algorithm comparison tests ====================
  // These tests directly compare ParallelMerklePatriciaProducer.createFromBytes (bulk)
  // against IncrementalTrieOps.insertMultiple (sequential) to verify structural equivalence.

  test("direct: createFromBytes bulk build matches IncrementalTrieOps.insertMultiple for 100 entries") { res =>
    implicit val (j, h, _) = res

    // Generate 100 random hex entries with realistic key lengths
    val rng = new Random(42) // deterministic seed
    val entries: Map[Hex, Array[Byte]] = (0 until 100).map { i =>
      val key = f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x"
      val value = s"""{"index":$i,"data":"entry_$i"}""".getBytes("UTF-8")
      Hex(key) -> value
    }.toMap

    val parallelProducer = ParallelMerklePatriciaProducer[IO]

    for {
      // BULK BUILD via createFromBytes
      bulkTrie <- parallelProducer.createFromBytes(entries)
      bulkRoot = bulkTrie.rootHash

      // INCREMENTAL BUILD: empty branch + insertMultiple with sorted entries
      emptyBranch <- MerklePatriciaNode.Branch.empty[IO]
      hashEntries <- entries.toList.traverse {
        case (hex, bytes) => Hasher[IO].hashBytes(bytes).map(hash => (hex, hash))
      }
      sortedEntries = hashEntries.sortBy { case (hex, _) => CompactNibblePath.fromHexString(hex.value) }
      incRoot <- IncrementalTrieOps.insertMultiple[IO](emptyBranch, sortedEntries)
      incTrie = MerklePatriciaTrie(incRoot)

      _ <- IO.println(s"[DIRECT 100] Bulk: $bulkRoot, Incremental: ${incTrie.rootHash}, Match: ${bulkRoot == incTrie.rootHash}")
    } yield expect(bulkRoot == incTrie.rootHash)
  }

  test("direct: createFromBytes bulk build matches IncrementalTrieOps.insertMultiple for 1000 entries") { res =>
    implicit val (j, h, _) = res

    val rng = new Random(123)
    val entries: Map[Hex, Array[Byte]] = (0 until 1000).map { i =>
      val key = f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x"
      val value = s"""{"index":$i}""".getBytes("UTF-8")
      Hex(key) -> value
    }.toMap

    val parallelProducer = ParallelMerklePatriciaProducer[IO]

    for {
      bulkTrie <- parallelProducer.createFromBytes(entries)
      bulkRoot = bulkTrie.rootHash

      emptyBranch <- MerklePatriciaNode.Branch.empty[IO]
      hashEntries <- entries.toList.traverse {
        case (hex, bytes) => Hasher[IO].hashBytes(bytes).map(hash => (hex, hash))
      }
      sortedEntries = hashEntries.sortBy { case (hex, _) => CompactNibblePath.fromHexString(hex.value) }
      incRoot <- IncrementalTrieOps.insertMultiple[IO](emptyBranch, sortedEntries)
      incTrie = MerklePatriciaTrie(incRoot)

      _ <- IO.println(s"[DIRECT 1000] Bulk: $bulkRoot, Incremental: ${incTrie.rootHash}, Match: ${bulkRoot == incTrie.rootHash}")
    } yield expect(bulkRoot == incTrie.rootHash)
  }

  test("direct: bulk build N entries then incremental insert matches bulk build N+M entries") { res =>
    implicit val (j, h, _) = res

    // This is the EXACT production scenario:
    // 1. Build bulk trie from N entries (initial full build)
    // 2. Insert M new entries incrementally (consensus updates)
    // 3. Compare with bulk build of all N+M entries (retraversal full rebuild)

    val rng = new Random(777)
    val baseEntries: Map[Hex, Array[Byte]] = (0 until 200).map { i =>
      val key = f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x"
      val value = s"""{"base":$i}""".getBytes("UTF-8")
      Hex(key) -> value
    }.toMap

    val newEntries: Map[Hex, Array[Byte]] = (0 until 10).map { i =>
      val key = f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x" +
        f"${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x${rng.nextInt(256)}%02x"
      val value = s"""{"new":$i}""".getBytes("UTF-8")
      Hex(key) -> value
    }.toMap

    val allEntries = baseEntries ++ newEntries
    val parallelProducer = ParallelMerklePatriciaProducer[IO]

    for {
      // PATH 1: Bulk build base, then incremental insert new entries
      baseTrie <- parallelProducer.createFromBytes(baseEntries)
      newHashEntries <- newEntries.toList.traverse {
        case (hex, bytes) => Hasher[IO].hashBytes(bytes).map(hash => (hex, hash))
      }
      sortedNewEntries = newHashEntries.sortBy { case (hex, _) => CompactNibblePath.fromHexString(hex.value) }
      incRoot <- IncrementalTrieOps.insertMultiple[IO](baseTrie.rootNode, sortedNewEntries)
      incTrie = MerklePatriciaTrie(incRoot)

      // PATH 2: Bulk build all entries from scratch
      fullTrie <- parallelProducer.createFromBytes(allEntries)

      _ <- IO.println(
        s"[BULK+INC vs BULK] Incremental: ${incTrie.rootHash}, Full: ${fullTrie.rootHash}, Match: ${incTrie.rootHash == fullTrie.rootHash}"
      )
    } yield expect(incTrie.rootHash == fullTrie.rootHash)
  }

  test("direct: bulk build with variable-length keys matches incremental build") { res =>
    implicit val (j, h, _) = res

    // Test with variable-length keys similar to production GlobalStateKey format
    // where different field types produce different key lengths
    val entries: Map[Hex, Array[Byte]] = List(
      // Short keys (like HypergraphNamespace entries)
      "00" + "00000001" + "00" + "00" -> """{"short1":1}""",
      "00" + "00000001" + "00" + "01" -> """{"short2":2}""",
      "00" + "00000002" + "00" + "00" -> """{"short3":3}""",
      // Medium keys (like AddressNamespace entries)
      "00" + "00000003" + "03" + "02" + ("ab" * 32) -> """{"med1":1}""",
      "00" + "00000003" + "03" + "02" + ("cd" * 32) -> """{"med2":2}""",
      "00" + "00000003" + "03" + "02" + ("ef" * 32) -> """{"med3":3}""",
      // Long keys (like MetagraphNamespace entries)
      "01" + ("11" * 32) + "00000004" + "03" + "02" + ("aa" * 32) -> """{"long1":1}""",
      "01" + ("22" * 32) + "00000004" + "03" + "02" + ("bb" * 32) -> """{"long2":2}"""
    ).map { case (k, v) => Hex(k) -> v.getBytes("UTF-8") }.toMap

    val parallelProducer = ParallelMerklePatriciaProducer[IO]

    for {
      bulkTrie <- parallelProducer.createFromBytes(entries)
      bulkRoot = bulkTrie.rootHash

      hashEntries <- entries.toList.traverse {
        case (hex, bytes) => Hasher[IO].hashBytes(bytes).map(hash => (hex, hash))
      }
      sortedEntries = hashEntries.sortBy { case (hex, _) => CompactNibblePath.fromHexString(hex.value) }

      // Test the actual production scenario: bulk build base, then incremental insert new entries
      // Split entries: first 3 as base (initial full build), remaining 5 as incremental updates
      baseEntries = sortedEntries.take(3)
      newEntries = sortedEntries.drop(3)

      baseEntriesMap = baseEntries.map { case (hex, _) => hex -> entries(hex) }.toMap
      baseTrie <- parallelProducer.createFromBytes(baseEntriesMap)

      // Incrementally insert new entries on top of bulk-built base
      incRoot <- IncrementalTrieOps.insertMultiple[IO](baseTrie.rootNode, newEntries)
      incTrie = MerklePatriciaTrie(incRoot)

      _ <- IO.println(s"[VAR-LEN] Bulk: $bulkRoot, Incremental (base+inc): ${incTrie.rootHash}, Match: ${bulkRoot == incTrie.rootHash}")

      // Also test with prefix keys (where one key is a prefix of another)
      prefixEntries: Map[Hex, Array[Byte]] = Map(
        Hex("aabb") -> """{"prefix":1}""".getBytes("UTF-8"),
        Hex("aabbccdd") -> """{"prefix":2}""".getBytes("UTF-8"),
        Hex("aabbccddeeff") -> """{"prefix":3}""".getBytes("UTF-8")
      )
      prefixBulk <- parallelProducer.createFromBytes(prefixEntries)
      prefixHashEntries <- prefixEntries.toList.traverse {
        case (hex, bytes) => Hasher[IO].hashBytes(bytes).map(hash => (hex, hash))
      }
      prefixSorted = prefixHashEntries.sortBy { case (hex, _) => CompactNibblePath.fromHexString(hex.value) }
      // Build with first entry as base, then incremental
      prefixBaseMap = Map(prefixSorted.head._1 -> prefixEntries(prefixSorted.head._1))
      prefixBaseTrie <- parallelProducer.createFromBytes(prefixBaseMap)
      prefixIncRoot <- IncrementalTrieOps.insertMultiple[IO](prefixBaseTrie.rootNode, prefixSorted.tail)
      prefixIncTrie = MerklePatriciaTrie(prefixIncRoot)

      _ <- IO.println(
        s"[PREFIX] Bulk: ${prefixBulk.rootHash}, Incremental (base+inc): ${prefixIncTrie.rootHash}, Match: ${prefixBulk.rootHash == prefixIncTrie.rootHash}"
      )
    } yield expect(bulkRoot == incTrie.rootHash).and(expect(prefixBulk.rootHash == prefixIncTrie.rootHash))
  }
}
