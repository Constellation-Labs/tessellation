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
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.StateChangesAccumulator
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.producer.InMemoryMerklePatriciaProducer
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

/** Test suite verifying that incremental MPT sync produces the same root hash as full sync.
  *
  * This tests the core hypothesis that StateChangesAccumulator.toStateEntries produces equivalent results to
  * GlobalSnapshotInfo.allStateEntries when both contain the same data.
  */
object MptIncrementalVsFullSyncSuite extends MutableIOSuite with Checkers {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  // Generate deterministic test addresses
  private val addr1 = addressGen.sample.get
  private val addr2 = addressGen.sample.get
  private val addr3 = addressGen.sample.get

  // Use MptRoot format for tests
  implicit val stateProofSelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  test("empty accumulator produces same entries as empty GlobalSnapshotInfo") { res =>
    implicit val (j, h, _) = res

    val emptyAccumulator = StateChangesAccumulator()
    val emptyInfo = GlobalSnapshotInfo.empty

    for {
      accEntries <- emptyAccumulator.toStateEntries[IO]
      infoEntries <- emptyInfo.allStateEntries[IO]
    } yield
      expect.all(
        accEntries.isEmpty,
        infoEntries.isEmpty,
        accEntries == infoEntries
      )
  }

  test("accumulator with balances produces same entries as GlobalSnapshotInfo with balances") { res =>
    implicit val (j, h, _) = res

    val balances = SortedMap(
      addr1 -> balance.Balance(1000L),
      addr2 -> balance.Balance(2000L),
      addr3 -> balance.Balance(3000L)
    )

    val accumulator = StateChangesAccumulator(balances = balances)
    val info = GlobalSnapshotInfo.empty.copy(balances = balances)

    for {
      accEntries <- accumulator.toStateEntries[IO]
      infoEntries <- info.allStateEntries[IO]
      _ <- IO.println(s"[DEBUG] Accumulator entries: ${accEntries.size}")
      _ <- IO.println(s"[DEBUG] Info entries: ${infoEntries.size}")

      // Check that both have the same keys
      accKeys = accEntries.keySet
      infoKeys = infoEntries.keySet
      missingInAcc = infoKeys -- accKeys
      missingInInfo = accKeys -- infoKeys
      _ <- IO.println(s"[DEBUG] Keys only in info: $missingInAcc")
      _ <- IO.println(s"[DEBUG] Keys only in accumulator: $missingInInfo")
    } yield
      expect.all(
        accEntries.size == 3,
        infoEntries.size == 3,
        accEntries == infoEntries
      )
  }

  test("accumulator with lastTxRefs produces same entries as GlobalSnapshotInfo") { res =>
    implicit val (j, h, _) = res

    val txRef = transactionReferenceGen.sample.get
    val lastTxRefs = SortedMap(addr1 -> txRef)

    val accumulator = StateChangesAccumulator(lastTxRefs = lastTxRefs)
    val info = GlobalSnapshotInfo.empty.copy(lastTxRefs = lastTxRefs)

    for {
      accEntries <- accumulator.toStateEntries[IO]
      infoEntries <- info.allStateEntries[IO]
    } yield expect.eql(accEntries, infoEntries)
  }

  test("accumulator with multiple field types produces same entries as GlobalSnapshotInfo") { res =>
    implicit val (j, h, _) = res

    val balances = SortedMap(
      addr1 -> balance.Balance(1000L),
      addr2 -> balance.Balance(2000L)
    )
    val lastTxRefs = SortedMap(
      addr1 -> transactionReferenceGen.sample.get,
      addr3 -> transactionReferenceGen.sample.get
    )
    val stateChannelHashes = SortedMap(
      addr2 -> Hash("abc123")
    )

    val accumulator = StateChangesAccumulator(
      balances = balances,
      lastTxRefs = lastTxRefs,
      lastStateChannelSnapshotHashes = stateChannelHashes
    )

    val info = GlobalSnapshotInfo.empty.copy(
      balances = balances,
      lastTxRefs = lastTxRefs,
      lastStateChannelSnapshotHashes = stateChannelHashes
    )

    for {
      accEntries <- accumulator.toStateEntries[IO]
      infoEntries <- info.allStateEntries[IO]
      _ <- IO.println(s"[DEBUG] Multi-field test - acc: ${accEntries.size}, info: ${infoEntries.size}")

      // Detailed diff
      diffKeys = (accEntries.keySet ++ infoEntries.keySet).filter { k =>
        accEntries.get(k) != infoEntries.get(k)
      }
      _ <- diffKeys.toList.traverse_ { k =>
        IO.println(s"[DEBUG] Key $k differs: acc=${accEntries.get(k)}, info=${infoEntries.get(k)}")
      }
    } yield expect.eql(accEntries, infoEntries)
  }

  test("MPT root hash matches between incremental sync and full sync") { res =>
    implicit val (j, h, sp) = res

    val balances = SortedMap(
      addr1 -> balance.Balance(1000L),
      addr2 -> balance.Balance(2000L),
      addr3 -> balance.Balance(3000L)
    )
    val lastTxRefs = SortedMap(
      addr1 -> transactionReferenceGen.sample.get
    )

    val accumulator = StateChangesAccumulator(
      balances = balances,
      lastTxRefs = lastTxRefs
    )

    val info = GlobalSnapshotInfo.empty.copy(
      balances = balances,
      lastTxRefs = lastTxRefs
    )

    val ordinal = SnapshotOrdinal(NonNegLong(1000L))

    for {
      // Build MPT via full sync path (GlobalSnapshotInfo.allStateEntries)
      fullProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
      _ <- fullStore.syncFromGlobalSnapshotInfo(info, ordinal)
      fullTrie <- fullStore.build(ordinal)
      fullRoot = fullTrie.map(_.rootHash)

      // Build MPT via incremental sync path (StateChangesAccumulator.toStateEntries)
      incProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])
      _ <- incStore.syncFromStateChanges(accumulator, ordinal)
      incTrie <- incStore.build(ordinal)
      incRoot = incTrie.map(_.rootHash)

      _ <- IO.println(s"[DEBUG] Full sync root: $fullRoot")
      _ <- IO.println(s"[DEBUG] Incremental sync root: $incRoot")
    } yield
      expect.all(
        fullRoot.isRight,
        incRoot.isRight,
        fullRoot == incRoot
      )
  }

  test("MPT root hash matches after applying incremental updates") { res =>
    implicit val (j, h, sp) = res

    // Initial state
    val initialBalances = SortedMap(
      addr1 -> balance.Balance(1000L),
      addr2 -> balance.Balance(2000L)
    )

    // Updated state (addr1 balance changed, addr3 added)
    val finalBalances = SortedMap(
      addr1 -> balance.Balance(1500L),
      addr2 -> balance.Balance(2000L),
      addr3 -> balance.Balance(500L)
    )

    val ordinal1 = SnapshotOrdinal(NonNegLong(1000L))
    val ordinal2 = SnapshotOrdinal(NonNegLong(1001L))

    for {
      // Build MPT via incremental path
      incProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])

      // Apply initial state
      initialAcc = StateChangesAccumulator(balances = initialBalances)
      _ <- incStore.syncFromStateChanges(initialAcc, ordinal1)

      // Apply incremental update (only changed/new entries)
      updateAcc = StateChangesAccumulator(
        balances = SortedMap(
          addr1 -> balance.Balance(1500L), // changed
          addr3 -> balance.Balance(500L) // new
        )
      )
      _ <- incStore.syncFromStateChanges(updateAcc, ordinal2)
      incTrie <- incStore.build(ordinal2)
      incRoot = incTrie.map(_.rootHash)

      // Build MPT via full sync from final state
      fullProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
      finalInfo = GlobalSnapshotInfo.empty.copy(balances = finalBalances)
      _ <- fullStore.syncFromGlobalSnapshotInfo(finalInfo, ordinal2)
      fullTrie <- fullStore.build(ordinal2)
      fullRoot = fullTrie.map(_.rootHash)

      _ <- IO.println(s"[DEBUG] Incremental root after update: $incRoot")
      _ <- IO.println(s"[DEBUG] Full sync root of final state: $fullRoot")
    } yield
      expect.all(
        fullRoot.isRight,
        incRoot.isRight,
        fullRoot == incRoot
      )
  }

  test("entry-level comparison identifies differences between sync paths") { res =>
    implicit val (j, h, _) = res

    // Intentionally create mismatched data to test the comparison
    val accBalances = SortedMap(
      addr1 -> balance.Balance(1000L),
      addr2 -> balance.Balance(2000L)
    )
    val infoBalances = SortedMap(
      addr1 -> balance.Balance(1000L),
      addr2 -> balance.Balance(2500L), // Different!
      addr3 -> balance.Balance(500L) // Extra in info
    )

    val accumulator = StateChangesAccumulator(balances = accBalances)
    val info = GlobalSnapshotInfo.empty.copy(balances = infoBalances)

    for {
      accEntries <- accumulator.toStateEntries[IO]
      infoEntries <- info.allStateEntries[IO]

      // Simple inline comparison
      accKeys = accEntries.keySet
      infoKeys = infoEntries.keySet
      onlyInAcc = accKeys -- infoKeys
      onlyInInfo = infoKeys -- accKeys
      differentValues = accKeys.intersect(infoKeys).filter(k => accEntries(k) != infoEntries(k))

      _ <- IO.println(
        s"[DEBUG] Entry diff - onlyInAcc: ${onlyInAcc.size}, onlyInInfo: ${onlyInInfo.size}, valueDiffs: ${differentValues.size}"
      )
    } yield
      expect.all(
        onlyInAcc.nonEmpty || onlyInInfo.nonEmpty || differentValues.nonEmpty,
        // addr3 should be only in info
        onlyInInfo.nonEmpty,
        // addr2 should have different value
        differentValues.nonEmpty
      )
  }

  test("print summary of state entries for debugging") { res =>
    implicit val (j, h, _) = res

    val balances = SortedMap(
      addr1 -> balance.Balance(1000L),
      addr2 -> balance.Balance(2000L)
    )
    val lastTxRefs = SortedMap(
      addr1 -> transactionReferenceGen.sample.get
    )

    val accumulator = StateChangesAccumulator(
      balances = balances,
      lastTxRefs = lastTxRefs
    )

    for {
      entries <- accumulator.toStateEntries[IO]
      _ <- IO.println("\n=== State Entries Summary ===")
      _ <- entries.toList.traverse_ {
        case (key, value) =>
          val fieldId = key.fieldId
          val valuePreview = value.noSpaces.take(50)
          IO.println(s"Field: $fieldId, Key: ${key.userNamespace}, Value: $valuePreview...")
      }
      _ <- IO.println(s"Total entries: ${entries.size}")
      _ <- IO.println("==============================\n")
    } yield success
  }

  // Helper to create a mock signed delegated stake
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

  test("accumulator with delegated stakes produces same entries as GlobalSnapshotInfo") { res =>
    implicit val (j, h, _) = res

    val nodeId = Id(Hex("1234567890abcdef" * 8)).toPeerId
    val stake1 = createSignedStake(addr1, nodeId, 1000L)
    val stake2 = createSignedStake(addr2, nodeId, 2000L)

    val delegatedStakes = SortedMap(
      addr1 -> SortedSet(DelegatedStakeRecord(stake1, SnapshotOrdinal(1L), balance.Balance(50L), None, None)),
      addr2 -> SortedSet(DelegatedStakeRecord(stake2, SnapshotOrdinal(1L), balance.Balance(75L), None, None))
    )

    val accumulator = StateChangesAccumulator(activeDelegatedStakes = delegatedStakes)
    val info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(delegatedStakes))

    for {
      accEntries <- accumulator.toStateEntries[IO]
      infoEntries <- info.allStateEntries[IO]

      _ <- IO.println(s"[DEBUG] Delegated stakes test - acc entries: ${accEntries.size}, info entries: ${infoEntries.size}")

      // Check key sets match
      accKeys = accEntries.keySet
      infoKeys = infoEntries.keySet
      _ <- IO.println(s"[DEBUG] Keys only in accumulator: ${(accKeys -- infoKeys).size}")
      _ <- IO.println(s"[DEBUG] Keys only in info: ${(infoKeys -- accKeys).size}")

      // Check values match
      differentValues = accKeys.intersect(infoKeys).filter(k => accEntries(k) != infoEntries(k))
      _ <- differentValues.toList.traverse_ { k =>
        IO.println(s"[DEBUG] Value differs for key $k") >>
          IO.println(s"[DEBUG]   acc: ${accEntries(k).noSpaces.take(200)}") >>
          IO.println(s"[DEBUG]   info: ${infoEntries(k).noSpaces.take(200)}")
      }
    } yield
      expect.all(
        accEntries.size == 2,
        infoEntries.size == 2,
        accEntries == infoEntries
      )
  }

  test("MPT root hash matches with delegated stakes") { res =>
    implicit val (j, h, sp) = res

    val nodeId = Id(Hex("1234567890abcdef" * 8)).toPeerId
    val stake1 = createSignedStake(addr1, nodeId, 1000L)
    val stake2 = createSignedStake(addr2, nodeId, 2000L)

    val delegatedStakes = SortedMap(
      addr1 -> SortedSet(DelegatedStakeRecord(stake1, SnapshotOrdinal(1L), balance.Balance(50L), None, None)),
      addr2 -> SortedSet(DelegatedStakeRecord(stake2, SnapshotOrdinal(1L), balance.Balance(75L), None, None))
    )

    val accumulator = StateChangesAccumulator(activeDelegatedStakes = delegatedStakes)
    val info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(delegatedStakes))

    val ordinal = SnapshotOrdinal(NonNegLong(1000L))

    for {
      // Build MPT via full sync
      fullProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      fullStore <- MptStore.make[IO, GlobalStateKey](fullProducer, GlobalStateKey.toHex[IO])
      _ <- fullStore.syncFromGlobalSnapshotInfo(info, ordinal)
      fullTrie <- fullStore.build(ordinal)
      fullRoot = fullTrie.map(_.rootHash)

      // Build MPT via incremental sync
      incProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      incStore <- MptStore.make[IO, GlobalStateKey](incProducer, GlobalStateKey.toHex[IO])
      _ <- incStore.syncFromStateChanges(accumulator, ordinal)
      incTrie <- incStore.build(ordinal)
      incRoot = incTrie.map(_.rootHash)

      _ <- IO.println(s"[DEBUG] Delegated stakes MPT - Full sync root: $fullRoot")
      _ <- IO.println(s"[DEBUG] Delegated stakes MPT - Incremental sync root: $incRoot")
    } yield
      expect.all(
        fullRoot.isRight,
        incRoot.isRight,
        fullRoot == incRoot
      )
  }

  // ========== Serialization round-trip tests ==========
  // These simulate what happens when state is written to disk and read back

  test("DelegatedStakeRecord survives JSON round-trip with identical encoding") { res =>
    implicit val (j, h, _) = res

    val nodeId = Id(Hex("1234567890abcdef" * 8)).toPeerId
    val stake = createSignedStake(addr1, nodeId, 1000L)
    val record = DelegatedStakeRecord(stake, SnapshotOrdinal(1L), balance.Balance(50L), None, None)

    import io.circe.syntax._
    import io.circe.parser._

    val json1 = record.asJson
    val roundTrip = decode[DelegatedStakeRecord](json1.noSpaces)
    val json2 = roundTrip.map(_.asJson)

    for {
      _ <- IO.println(s"[DEBUG] Original JSON: ${json1.noSpaces.take(200)}...")
      _ <- IO.println(s"[DEBUG] Round-trip JSON: ${json2.map(_.noSpaces.take(200))}...")
    } yield
      expect.all(
        roundTrip.isRight,
        json2.map(_.noSpaces) == Right(json1.noSpaces)
      )
  }

  test("SortedSet[DelegatedStakeRecord] survives JSON round-trip with identical encoding") { res =>
    implicit val (j, h, _) = res

    val nodeId = Id(Hex("1234567890abcdef" * 8)).toPeerId
    val stake1 = createSignedStake(addr1, nodeId, 1000L)
    val stake2 = createSignedStake(addr1, nodeId, 2000L, Hash("abc"))

    // Multiple records for same address - ordering matters!
    val records = SortedSet(
      DelegatedStakeRecord(stake1, SnapshotOrdinal(1L), balance.Balance(50L), None, None),
      DelegatedStakeRecord(stake2, SnapshotOrdinal(2L), balance.Balance(100L), None, None)
    )

    import io.circe.syntax._
    import io.circe.parser._

    val json1 = records.asJson
    val roundTrip = decode[SortedSet[DelegatedStakeRecord]](json1.noSpaces)
    val json2 = roundTrip.map(_.asJson)

    for {
      _ <- IO.println(s"[DEBUG] Original set size: ${records.size}")
      _ <- IO.println(s"[DEBUG] Round-trip set size: ${roundTrip.map(_.size)}")
      _ <- IO.println(s"[DEBUG] JSON match: ${json2.map(_.noSpaces) == Right(json1.noSpaces)}")
    } yield
      expect.all(
        roundTrip.isRight,
        roundTrip.map(_.size) == Right(records.size),
        json2.map(_.noSpaces) == Right(json1.noSpaces)
      )
  }

  test("GlobalSnapshotInfo with delegated stakes survives JSON round-trip") { res =>
    implicit val (j, h, _) = res

    val nodeId = Id(Hex("1234567890abcdef" * 8)).toPeerId
    val stake1 = createSignedStake(addr1, nodeId, 1000L)
    val stake2 = createSignedStake(addr2, nodeId, 2000L)

    val delegatedStakes = SortedMap(
      addr1 -> SortedSet(DelegatedStakeRecord(stake1, SnapshotOrdinal(1L), balance.Balance(50L), None, None)),
      addr2 -> SortedSet(DelegatedStakeRecord(stake2, SnapshotOrdinal(1L), balance.Balance(75L), None, None))
    )

    val info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(delegatedStakes))

    import io.circe.syntax._
    import io.circe.parser._

    val json1 = info.asJson
    val roundTrip = decode[GlobalSnapshotInfo](json1.noSpaces)

    for {
      _ <- IO.println(s"[DEBUG] Original info delegatedStakes: ${info.activeDelegatedStakes.map(_.size)}")
      _ <- IO.println(s"[DEBUG] Round-trip delegatedStakes: ${roundTrip.map(_.activeDelegatedStakes.map(_.size))}")

      // Now compare state entries from both
      originalEntries <- info.allStateEntries[IO]
      roundTripEntries <- roundTrip.fold(
        err => IO.raiseError(new RuntimeException(s"Decode failed: $err")),
        rt => rt.allStateEntries[IO]
      )

      _ <- IO.println(s"[DEBUG] Original entries: ${originalEntries.size}")
      _ <- IO.println(s"[DEBUG] Round-trip entries: ${roundTripEntries.size}")

      // Check for differences
      diffKeys = (originalEntries.keySet ++ roundTripEntries.keySet).filter { k =>
        originalEntries.get(k) != roundTripEntries.get(k)
      }
      _ <- diffKeys.toList.traverse_ { k =>
        IO.println(s"[DEBUG] Entry differs for $k") >>
          IO.println(s"[DEBUG]   original: ${originalEntries.get(k).map(_.noSpaces.take(100))}") >>
          IO.println(s"[DEBUG]   roundtrip: ${roundTripEntries.get(k).map(_.noSpaces.take(100))}")
      }
    } yield
      expect.all(
        roundTrip.isRight,
        originalEntries.size == roundTripEntries.size,
        originalEntries == roundTripEntries
      )
  }

  test("MPT root hash matches after GlobalSnapshotInfo JSON round-trip") { res =>
    implicit val (j, h, sp) = res

    val nodeId = Id(Hex("1234567890abcdef" * 8)).toPeerId
    val stake1 = createSignedStake(addr1, nodeId, 1000L)
    val stake2 = createSignedStake(addr2, nodeId, 2000L)

    val delegatedStakes = SortedMap(
      addr1 -> SortedSet(DelegatedStakeRecord(stake1, SnapshotOrdinal(1L), balance.Balance(50L), None, None)),
      addr2 -> SortedSet(DelegatedStakeRecord(stake2, SnapshotOrdinal(1L), balance.Balance(75L), None, None))
    )

    val info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(delegatedStakes))
    val ordinal = SnapshotOrdinal(NonNegLong(1000L))

    import io.circe.syntax._
    import io.circe.parser._

    for {
      // Build MPT from original
      originalProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      originalStore <- MptStore.make[IO, GlobalStateKey](originalProducer, GlobalStateKey.toHex[IO])
      _ <- originalStore.syncFromGlobalSnapshotInfo(info, ordinal)
      originalTrie <- originalStore.build(ordinal)
      originalRoot = originalTrie.map(_.rootHash)

      // Serialize and deserialize GlobalSnapshotInfo
      json = info.asJson
      roundTripInfo <- IO.fromEither(decode[GlobalSnapshotInfo](json.noSpaces))

      // Build MPT from round-trip info
      rtProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      rtStore <- MptStore.make[IO, GlobalStateKey](rtProducer, GlobalStateKey.toHex[IO])
      _ <- rtStore.syncFromGlobalSnapshotInfo(roundTripInfo, ordinal)
      rtTrie <- rtStore.build(ordinal)
      rtRoot = rtTrie.map(_.rootHash)

      _ <- IO.println(s"[DEBUG] Original MPT root: $originalRoot")
      _ <- IO.println(s"[DEBUG] Round-trip MPT root: $rtRoot")
    } yield
      expect.all(
        originalRoot.isRight,
        rtRoot.isRight,
        originalRoot == rtRoot
      )
  }

  test("MPT entries survive being extracted and rebuilt") { res =>
    implicit val (j, h, sp) = res

    val nodeId = Id(Hex("1234567890abcdef" * 8)).toPeerId
    val stake1 = createSignedStake(addr1, nodeId, 1000L)

    val delegatedStakes = SortedMap(
      addr1 -> SortedSet(DelegatedStakeRecord(stake1, SnapshotOrdinal(1L), balance.Balance(50L), None, None))
    )

    val info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(delegatedStakes))
    val ordinal = SnapshotOrdinal(NonNegLong(1000L))

    for {
      // Build first MPT
      producer1 <- InMemoryMerklePatriciaProducer.make[IO]()
      store1 <- MptStore.make[IO, GlobalStateKey](producer1, GlobalStateKey.toHex[IO])
      _ <- store1.syncFromGlobalSnapshotInfo(info, ordinal)
      trie1 <- store1.build(ordinal)
      root1 = trie1.map(_.rootHash)

      // Get entries from underlying producer
      entries1 <- producer1.entries

      // Build second MPT from extracted entries
      producer2 <- InMemoryMerklePatriciaProducer.make[IO]()
      _ <- producer2.insertBytes(entries1)
      trie2 <- producer2.buildForOrdinal(ordinal)
      root2 = trie2.map(_.rootHash)

      _ <- IO.println(s"[DEBUG] Original MPT root: $root1")
      _ <- IO.println(s"[DEBUG] Rebuilt from entries MPT root: $root2")
      _ <- IO.println(s"[DEBUG] Entries count: ${entries1.size}")
    } yield
      expect.all(
        root1.isRight,
        root2.isRight,
        root1 == root2
      )
  }

  // ========== Simulating consensus->persist->reload->validate flow ==========

  test("simulate: consensus builds incrementally, then validate rebuilds from full state") { res =>
    implicit val (j, h, sp) = res

    val nodeId = Id(Hex("1234567890abcdef" * 8)).toPeerId

    // Ordinal 1: Initial state with one delegated stake
    val stake1 = createSignedStake(addr1, nodeId, 1000L)
    val ordinal1 = SnapshotOrdinal(NonNegLong(1L))
    val delegatedStakes1 = SortedMap(
      addr1 -> SortedSet(DelegatedStakeRecord(stake1, ordinal1, balance.Balance(0L), None, None))
    )
    val acc1 = StateChangesAccumulator(activeDelegatedStakes = delegatedStakes1)

    // Ordinal 2: Add another delegated stake
    val stake2 = createSignedStake(addr2, nodeId, 2000L)
    val ordinal2 = SnapshotOrdinal(NonNegLong(2L))
    val delegatedStakes2 = SortedMap(
      addr1 -> SortedSet(DelegatedStakeRecord(stake1, ordinal1, balance.Balance(10L), None, None)),
      addr2 -> SortedSet(DelegatedStakeRecord(stake2, ordinal2, balance.Balance(0L), None, None))
    )
    val acc2Changes = StateChangesAccumulator(
      activeDelegatedStakes = SortedMap(
        addr1 -> SortedSet(DelegatedStakeRecord(stake1, ordinal1, balance.Balance(10L), None, None)),
        addr2 -> SortedSet(DelegatedStakeRecord(stake2, ordinal2, balance.Balance(0L), None, None))
      )
    )
    val info2 = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(delegatedStakes2))

    for {
      // CONSENSUS PATH: Build MPT incrementally
      consensusProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      consensusStore <- MptStore.make[IO, GlobalStateKey](consensusProducer, GlobalStateKey.toHex[IO])

      _ <- consensusStore.syncFromStateChanges(acc1, ordinal1)
      _ <- consensusStore.syncFromStateChanges(acc2Changes, ordinal2)
      consensusTrie2 <- consensusStore.build(ordinal2)
      consensusRoot2 = consensusTrie2.map(_.rootHash)

      // VALIDATION PATH: Build MPT from full state
      validationProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      validationStore <- MptStore.make[IO, GlobalStateKey](validationProducer, GlobalStateKey.toHex[IO])

      _ <- validationStore.syncFromGlobalSnapshotInfo(info2, ordinal2)
      validationTrie2 <- validationStore.build(ordinal2)
      validationRoot2 = validationTrie2.map(_.rootHash)

      _ <- IO.println(s"[DEBUG] Consensus root at ordinal 2: $consensusRoot2")
      _ <- IO.println(s"[DEBUG] Validation root at ordinal 2: $validationRoot2")
    } yield
      expect.all(
        consensusRoot2.isRight,
        validationRoot2.isRight,
        consensusRoot2 == validationRoot2
      )
  }

  test("simulate: multiple delegated stakes for same address") { res =>
    implicit val (j, h, sp) = res

    val nodeId1 = Id(Hex("1111111111111111" * 8)).toPeerId
    val nodeId2 = Id(Hex("2222222222222222" * 8)).toPeerId

    val stake1 = createSignedStake(addr1, nodeId1, 1000L)
    val stake2 = createSignedStake(addr1, nodeId2, 2000L, Hash("different"))

    val ordinal = SnapshotOrdinal(NonNegLong(1L))
    val record1 = DelegatedStakeRecord(stake1, ordinal, balance.Balance(0L), None, None)
    val record2 = DelegatedStakeRecord(stake2, ordinal, balance.Balance(0L), None, None)

    val delegatedStakes = SortedMap(
      addr1 -> SortedSet(record1, record2)
    )

    val accumulator = StateChangesAccumulator(activeDelegatedStakes = delegatedStakes)
    val info = GlobalSnapshotInfo.empty.copy(activeDelegatedStakes = Some(delegatedStakes))

    for {
      accEntries <- accumulator.toStateEntries[IO]
      infoEntries <- info.allStateEntries[IO]

      _ <- IO.println(s"[DEBUG] Multiple stakes - entries match: ${accEntries == infoEntries}")

      accProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      accStore <- MptStore.make[IO, GlobalStateKey](accProducer, GlobalStateKey.toHex[IO])
      _ <- accStore.syncFromStateChanges(accumulator, ordinal)
      accTrie <- accStore.build(ordinal)
      accRoot = accTrie.map(_.rootHash)

      infoProducer <- InMemoryMerklePatriciaProducer.make[IO]()
      infoStore <- MptStore.make[IO, GlobalStateKey](infoProducer, GlobalStateKey.toHex[IO])
      _ <- infoStore.syncFromGlobalSnapshotInfo(info, ordinal)
      infoTrie <- infoStore.build(ordinal)
      infoRoot = infoTrie.map(_.rootHash)

      _ <- IO.println(s"[DEBUG] Multiple stakes - roots match: ${accRoot == infoRoot}")
    } yield
      expect.all(
        accEntries == infoEntries,
        accRoot == infoRoot
      )
  }
}
