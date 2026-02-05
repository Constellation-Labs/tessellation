package io.constellationnetwork.security.mpt

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.StateChangesAccumulator
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.producer.InMemoryMerklePatriciaProducer
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

/**
  * Test suite verifying that incremental MPT sync produces the same root hash as full sync.
  * 
  * This tests the core hypothesis that StateChangesAccumulator.toStateEntries produces 
  * equivalent results to GlobalSnapshotInfo.allStateEntries when both contain the same data.
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
    } yield expect.all(
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
    } yield expect.all(
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
    } yield expect.all(
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
          addr1 -> balance.Balance(1500L),  // changed
          addr3 -> balance.Balance(500L)     // new
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
    } yield expect.all(
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
      addr3 -> balance.Balance(500L)   // Extra in info
    )

    val accumulator = StateChangesAccumulator(balances = accBalances)
    val info = GlobalSnapshotInfo.empty.copy(balances = infoBalances)

    for {
      accEntries <- accumulator.toStateEntries[IO]
      infoEntries <- info.allStateEntries[IO]
      
      diff <- MptFieldDigests.compareEntrySets[IO](accEntries, infoEntries, "accumulator", "info", "test-comparison")
      
      _ <- IO.println(s"[DEBUG] Entry diff - onlyInFirst: ${diff.onlyInA.size}, onlyInSecond: ${diff.onlyInB.size}, valueDiffs: ${diff.differentValues.size}")
    } yield expect.all(
      diff.hasDifferences,
      // addr3 should be only in info (second set)
      diff.onlyInB.nonEmpty,
      // addr2 should have different value
      diff.differentValues.nonEmpty
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
      _ <- entries.toList.traverse_ { case (key, value) =>
        val fieldId = key.fieldId
        val valuePreview = value.noSpaces.take(50)
        IO.println(s"Field: $fieldId, Key: ${key.userNamespace}, Value: $valuePreview...")
      }
      _ <- IO.println(s"Total entries: ${entries.size}")
      _ <- IO.println("==============================\n")
    } yield success
  }
}
