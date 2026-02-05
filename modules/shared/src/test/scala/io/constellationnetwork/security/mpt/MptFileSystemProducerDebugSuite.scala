package io.constellationnetwork.security.mpt

import cats.data.NonEmptySet
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

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
import io.constellationnetwork.security.mpt.producer.{FileSystemMerklePatriciaProducer, MerklePatriciaError}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.{Files, Path}
import weaver.MutableIOSuite

/**
  * Debug test suite using FileSystemMerklePatriciaProducer.
  * 
  * This tests the ACTUAL production code path with disk persistence
  * and incremental trie updates.
  */
object MptFileSystemProducerDebugSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  private val addr1 = addressGen.sample.get
  private val addr2 = addressGen.sample.get
  private val addr3 = addressGen.sample.get

  implicit val stateProofSelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

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

  private val nodeId1 = Id(Hex("1111111111111111" * 8)).toPeerId
  private val nodeId2 = Id(Hex("2222222222222222" * 8)).toPeerId

  // Create a temporary directory for each test
  private def withTempDir[A](test: Path => IO[A]): IO[A] =
    Files[IO].tempDirectory.use(test)

  test("filesystem: create single delegated stake - incremental vs full rebuild") { res =>
    implicit val (j, h, sp) = res

    val stake = createSignedStake(addr1, nodeId1, 1000L)
    val ordinal = SnapshotOrdinal(NonNegLong(1L))
    val record = DelegatedStakeRecord(stake, ordinal, Balance(0L), None, None)
    val delegatedStakes = SortedMap(addr1 -> SortedSet(record))

    withTempDir { dir =>
      for {
        // INCREMENTAL PATH: Build via StateChangesAccumulator
        incProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "incremental")
        incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])
        acc = StateChangesAccumulator(activeDelegatedStakes = delegatedStakes)
        _ <- incStore.syncFromStateChanges(acc, ordinal)
        incTrie <- incStore.build(ordinal)
        incRoot = incTrie.map(_.rootHash)

        // FULL PATH: Build via GlobalSnapshotInfo.allStateEntries
        fullProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "full")
        fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
        info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(delegatedStakes))
        _ <- fullStore.syncFromGlobalSnapshotInfo(info, ordinal)
        fullTrie <- fullStore.build(ordinal)
        fullRoot = fullTrie.map(_.rootHash)

        _ <- IO.println(s"[FS CREATE] Inc: $incRoot, Full: $fullRoot, Match: ${incRoot == fullRoot}")
      } yield expect(incRoot == fullRoot)
    }
  }

  test("filesystem: create then update rewards") { res =>
    implicit val (j, h, sp) = res

    val stake = createSignedStake(addr1, nodeId1, 1000L)
    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    val record1 = DelegatedStakeRecord(stake, ordinal1, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake, ordinal1, Balance(100L), None, None)

    withTempDir { dir =>
      for {
        // INCREMENTAL: Apply changes at each ordinal
        incProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "incremental")
        incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])

        acc1 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record1)))
        _ <- incStore.syncFromStateChanges(acc1, ordinal1)
        _ <- incStore.build(ordinal1) // Build to update trie cache

        acc2 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record2)))
        _ <- incStore.syncFromStateChanges(acc2, ordinal2)
        incTrie <- incStore.build(ordinal2)
        incRoot = incTrie.map(_.rootHash)

        // FULL: Build from final state only
        fullProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "full")
        fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
        info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(SortedMap(addr1 -> SortedSet(record2))))
        _ <- fullStore.syncFromGlobalSnapshotInfo(info, ordinal2)
        fullTrie <- fullStore.build(ordinal2)
        fullRoot = fullTrie.map(_.rootHash)

        _ <- IO.println(s"[FS UPDATE] Inc: $incRoot, Full: $fullRoot, Match: ${incRoot == fullRoot}")
      } yield expect(incRoot == fullRoot)
    }
  }

  test("filesystem: add second stake for same address") { res =>
    implicit val (j, h, sp) = res

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr1, nodeId2, 2000L, Hash("different"))
    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    val record1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal2, Balance(0L), None, None)

    withTempDir { dir =>
      for {
        // INCREMENTAL
        incProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "incremental")
        incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])

        acc1 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record1)))
        _ <- incStore.syncFromStateChanges(acc1, ordinal1)
        _ <- incStore.build(ordinal1)

        // At ordinal 2, accumulator has BOTH records
        acc2 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record1, record2)))
        _ <- incStore.syncFromStateChanges(acc2, ordinal2)
        incTrie <- incStore.build(ordinal2)
        incRoot = incTrie.map(_.rootHash)

        // FULL
        fullProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "full")
        fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
        info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(SortedMap(addr1 -> SortedSet(record1, record2))))
        _ <- fullStore.syncFromGlobalSnapshotInfo(info, ordinal2)
        fullTrie <- fullStore.build(ordinal2)
        fullRoot = fullTrie.map(_.rootHash)

        _ <- IO.println(s"[FS ADD STAKE] Inc: $incRoot, Full: $fullRoot, Match: ${incRoot == fullRoot}")
      } yield expect(incRoot == fullRoot)
    }
  }

  test("filesystem: multiple addresses across ordinals") { res =>
    implicit val (j, h, sp) = res

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId1, 2000L)
    val stake3 = createSignedStake(addr3, nodeId2, 3000L)

    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))
    val ordinal3 = SnapshotOrdinal(NonNegLong(3L))

    val record1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal2, Balance(0L), None, None)
    val record3 = DelegatedStakeRecord(stake3, ordinal3, Balance(0L), None, None)

    withTempDir { dir =>
      for {
        // INCREMENTAL
        incProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "incremental")
        incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])

        acc1 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record1)))
        _ <- incStore.syncFromStateChanges(acc1, ordinal1)
        _ <- incStore.build(ordinal1)

        acc2 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(
          addr1 -> SortedSet(record1),
          addr2 -> SortedSet(record2)
        ))
        _ <- incStore.syncFromStateChanges(acc2, ordinal2)
        _ <- incStore.build(ordinal2)

        acc3 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(
          addr1 -> SortedSet(record1),
          addr2 -> SortedSet(record2),
          addr3 -> SortedSet(record3)
        ))
        _ <- incStore.syncFromStateChanges(acc3, ordinal3)
        incTrie <- incStore.build(ordinal3)
        incRoot = incTrie.map(_.rootHash)

        // FULL
        fullProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "full")
        fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
        info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(SortedMap(
          addr1 -> SortedSet(record1),
          addr2 -> SortedSet(record2),
          addr3 -> SortedSet(record3)
        )))
        _ <- fullStore.syncFromGlobalSnapshotInfo(info, ordinal3)
        fullTrie <- fullStore.build(ordinal3)
        fullRoot = fullTrie.map(_.rootHash)

        _ <- IO.println(s"[FS MULTI ADDR] Inc: $incRoot, Full: $fullRoot, Match: ${incRoot == fullRoot}")
      } yield expect(incRoot == fullRoot)
    }
  }

  test("filesystem: persist, reload, then continue building") { res =>
    implicit val (j, h, sp) = res

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId1, 2000L)

    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    val record1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal2, Balance(0L), None, None)

    withTempDir { dir =>
      for {
        // Phase 1: Build and persist at ordinal 1
        producer1 <- FileSystemMerklePatriciaProducer.make[IO](dir / "data")
        store1 <- MptStore.make[IO, GlobalStateKey](producer1, GlobalStateKey.toHex[IO])

        acc1 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record1)))
        _ <- store1.syncFromStateChanges(acc1, ordinal1)
        _ <- store1.build(ordinal1)
        _ <- producer1.persist(ordinal1) // Persist to disk

        // Phase 2: Create NEW producer, load from disk, continue building
        producer2 <- FileSystemMerklePatriciaProducer.make[IO](dir / "data")
        loaded <- producer2.load(ordinal1)
        store2 <- MptStore.make[IO, GlobalStateKey](producer2, GlobalStateKey.toHex[IO])

        // Add more data at ordinal 2
        acc2 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(
          addr1 -> SortedSet(record1),
          addr2 -> SortedSet(record2)
        ))
        _ <- store2.syncFromStateChanges(acc2, ordinal2)
        incTrie <- store2.build(ordinal2)
        incRoot = incTrie.map(_.rootHash)

        // FULL rebuild for comparison
        fullProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "full")
        fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
        info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(SortedMap(
          addr1 -> SortedSet(record1),
          addr2 -> SortedSet(record2)
        )))
        _ <- fullStore.syncFromGlobalSnapshotInfo(info, ordinal2)
        fullTrie <- fullStore.build(ordinal2)
        fullRoot = fullTrie.map(_.rootHash)

        _ <- IO.println(s"[FS PERSIST/RELOAD] Loaded: $loaded, Inc: $incRoot, Full: $fullRoot, Match: ${incRoot == fullRoot}")
      } yield expect.all(
        loaded,
        incRoot == fullRoot
      )
    }
  }

  test("filesystem: simulate rollback scenario - build, persist, clear, reload full state") { res =>
    implicit val (j, h, sp) = res

    val stake = createSignedStake(addr1, nodeId1, 1000L)
    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    val record1 = DelegatedStakeRecord(stake, ordinal1, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake, ordinal1, Balance(100L), None, None)

    withTempDir { dir =>
      for {
        // CONSENSUS: Build incrementally
        consensusProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "consensus")
        consensusStore <- MptStore.make[IO, GlobalStateKey](consensusProducer, GlobalStateKey.toHex[IO])

        acc1 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record1)))
        _ <- consensusStore.syncFromStateChanges(acc1, ordinal1)
        _ <- consensusStore.build(ordinal1)

        acc2 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record2)))
        _ <- consensusStore.syncFromStateChanges(acc2, ordinal2)
        consensusTrie <- consensusStore.build(ordinal2)
        consensusRoot = consensusTrie.map(_.rootHash)

        // ROLLBACK: New producer, syncFullIfNeeded (like GlobalSnapshotTraverse does)
        rollbackProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "rollback")
        rollbackStore <- MptStore.make[IO, GlobalStateKey](rollbackProducer, GlobalStateKey.toHex[IO])

        // This simulates what happens in GlobalSnapshotTraverse:
        // mptStore.syncFullIfNeeded[Json](firstInfo.allStateEntries[F], firstInc.ordinal)
        info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(SortedMap(addr1 -> SortedSet(record2))))
        _ <- rollbackStore.syncFullIfNeeded[io.circe.Json](info.allStateEntries[IO], ordinal2)
        rollbackTrie <- rollbackStore.build(ordinal2)
        rollbackRoot = rollbackTrie.map(_.rootHash)

        _ <- IO.println(s"[FS ROLLBACK] Consensus: $consensusRoot, Rollback: $rollbackRoot, Match: ${consensusRoot == rollbackRoot}")
      } yield expect(consensusRoot == rollbackRoot)
    }
  }

  test("filesystem: delegated stake with removal") { res =>
    implicit val (j, h, sp) = res

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr2, nodeId1, 2000L)

    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))

    val record1 = DelegatedStakeRecord(stake1, ordinal1, Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal1, Balance(0L), None, None)

    withTempDir { dir =>
      for {
        // INCREMENTAL with removal
        incProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "incremental")
        incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])

        acc1 = StateChangesAccumulator(activeDelegatedStakes = SortedMap(
          addr1 -> SortedSet(record1),
          addr2 -> SortedSet(record2)
        ))
        _ <- incStore.syncFromStateChanges(acc1, ordinal1)
        _ <- incStore.build(ordinal1)

        // At ordinal 2, addr2's stake is removed
        acc2 = StateChangesAccumulator(
          activeDelegatedStakes = SortedMap(addr1 -> SortedSet(record1)),
          removedDelegatedStakeKeys = Set(addr2)
        )
        _ <- incStore.syncFromStateChanges(acc2, ordinal2)
        incTrie <- incStore.build(ordinal2)
        incRoot = incTrie.map(_.rootHash)

        // FULL with final state (only addr1)
        fullProducer <- FileSystemMerklePatriciaProducer.make[IO](dir / "full")
        fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
        info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(SortedMap(addr1 -> SortedSet(record1))))
        _ <- fullStore.syncFromGlobalSnapshotInfo(info, ordinal2)
        fullTrie <- fullStore.build(ordinal2)
        fullRoot = fullTrie.map(_.rootHash)

        _ <- IO.println(s"[FS REMOVAL] Inc: $incRoot, Full: $fullRoot, Match: ${incRoot == fullRoot}")
      } yield expect(incRoot == fullRoot)
    }
  }
}
