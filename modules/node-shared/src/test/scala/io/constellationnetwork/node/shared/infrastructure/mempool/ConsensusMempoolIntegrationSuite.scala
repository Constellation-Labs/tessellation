package io.constellationnetwork.node.shared.infrastructure.mempool

import java.time.Instant

import cats.data.NonEmptySet
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.mpt.GlobalStateFieldId.Balances
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.schema.mpt.PartitionNamespace.{AddressNamespace, EmptyNamespace, HypergraphNamespace}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.SimpleIOSuite

/** Integration tests verifying the consensus-mempool interaction flow.
  *
  * Tests the pattern used in GlobalSnapshotConsensusStateAdvancer and CurrencySnapshotConsensusStateAdvancer for pulling events from
  * mempool during consensus proposal building.
  */
object ConsensusMempoolIntegrationSuite extends SimpleIOSuite {

  // Test event type
  case class TestEvent(id: String, data: String)

  def makeStateKey(id: String): GlobalStateKey = {
    val addr = Address.fromBytes(id.getBytes)
    GlobalStateKey(HypergraphNamespace, Balances, EmptyNamespace, AddressNamespace(addr))
  }

  def makeSignedEvent(id: String, data: String): Signed[TestEvent] = {
    val mockProof = SignatureProof(Id(Hex("test")), Signature(Hex("sig")))
    Signed(TestEvent(id, data), NonEmptySet.one(mockProof))
  }

  def makeHashedEvent(id: String, data: String): Hashed[TestEvent] = {
    val signed = makeSignedEvent(id, data)
    Hashed(signed, Hash(id.padTo(64, '0')), ProofsHash("proof"))
  }

  def makeMempoolEntry(id: String, data: String, keys: Set[GlobalStateKey]): MempoolEntry[TestEvent, GlobalStateKey] =
    MempoolEntry(makeHashedEvent(id, data), keys, Instant.now())

  /** Extended mempool trait for testing with addEntry method */
  trait TestMempool extends EventMempool[IO, TestEvent, GlobalStateKey] {
    def addEntry(entry: MempoolEntry[TestEvent, GlobalStateKey]): IO[Unit]
  }

  /** Creates a test mempool algebra backed by a simple Ref-based implementation.
    */
  def makeTestMempool: IO[TestMempool] =
    Ref.of[IO, Map[Hash, MempoolEntry[TestEvent, GlobalStateKey]]](Map.empty).map { storageRef =>
      new TestMempool {
        def add(event: Signed[TestEvent]): IO[Either[MempoolRejectionReason, MempoolEntry[TestEvent, GlobalStateKey]]] =
          IO.raiseError(new NotImplementedError("Use addEntry for tests"))

        def get(hash: Hash): IO[Option[Hashed[TestEvent]]] =
          storageRef.get.map(_.get(hash).map(_.hashed))

        def getWithMeta(hash: Hash): IO[Option[MempoolEntry[TestEvent, GlobalStateKey]]] =
          storageRef.get.map(_.get(hash))

        def getMultiple(hashes: Set[Hash]): IO[Map[Hash, Hashed[TestEvent]]] =
          storageRef.get.map(_.filter { case (h, _) => hashes.contains(h) }.map { case (h, e) => h -> e.hashed })

        def remove(hashes: Set[Hash]): IO[Unit] =
          storageRef.update(_ -- hashes)

        def contains(hash: Hash): IO[Boolean] =
          storageRef.get.map(_.contains(hash))

        def snapshot(limit: Int = 10000): IO[MempoolSnapshot[TestEvent, GlobalStateKey]] =
          storageRef.get.map { store =>
            val bounded = store.toList.sortBy(_._2.receivedAt).take(limit).toMap
            MempoolSnapshot(bounded)
          }

        def clearIncluded(hashes: Set[Hash]): IO[Unit] =
          storageRef.update(_ -- hashes)

        def size: IO[Int] =
          storageRef.get.map(_.size)

        def addBatch(events: List[Signed[TestEvent]]): IO[List[Either[MempoolRejectionReason, MempoolEntry[TestEvent, GlobalStateKey]]]] =
          IO.raiseError(new NotImplementedError("Use addEntry for tests"))

        def getEventHashes: IO[SortedSet[Hash]] =
          storageRef.get.map(store => SortedSet.from(store.keySet))

        // Test helper to add entries directly
        def addEntry(entry: MempoolEntry[TestEvent, GlobalStateKey]): IO[Unit] =
          storageRef.update(_ + (entry.hashed.hash -> entry))
      }
    }

  test("consensus flow: snapshot returns all mempool events") {
    for {
      mempool <- makeTestMempool

      // Add events to mempool
      entry1 = makeMempoolEntry("event1", "data1", Set(makeStateKey("addr1")))
      entry2 = makeMempoolEntry("event2", "data2", Set(makeStateKey("addr2")))
      entry3 = makeMempoolEntry("event3", "data3", Set(makeStateKey("addr3")))
      _ <- mempool.addEntry(entry1)
      _ <- mempool.addEntry(entry2)
      _ <- mempool.addEntry(entry3)

      // Take snapshot (simulating consensus proposal building)
      snapshot <- mempool.snapshot()

    } yield
      expect.all(
        snapshot.size == 3,
        snapshot.hashes.contains(entry1.hashed.hash),
        snapshot.hashes.contains(entry2.hashed.hash),
        snapshot.hashes.contains(entry3.hashed.hash)
      )
  }

  test("consensus flow: snapshot provides value-to-hash mapping for cleanup") {
    for {
      mempool <- makeTestMempool

      // Add events to mempool
      entry1 = makeMempoolEntry("event1", "data1", Set(makeStateKey("addr1")))
      entry2 = makeMempoolEntry("event2", "data2", Set(makeStateKey("addr2")))
      _ <- mempool.addEntry(entry1)
      _ <- mempool.addEntry(entry2)

      // Take snapshot and build value-to-hash map (as done in state advancer)
      snapshot <- mempool.snapshot()
      events = snapshot.events.map(_.signed.value).toSet
      valueToHash = snapshot.events.map(h => h.signed.value -> h.hash).toMap

    } yield
      expect.all(
        events.size == 2,
        valueToHash.size == 2,
        valueToHash.get(TestEvent("event1", "data1")).contains(entry1.hashed.hash),
        valueToHash.get(TestEvent("event2", "data2")).contains(entry2.hashed.hash)
      )
  }

  test("consensus flow: clearIncluded removes processed events") {
    for {
      mempool <- makeTestMempool

      // Add events to mempool
      entry1 = makeMempoolEntry("event1", "data1", Set(makeStateKey("addr1")))
      entry2 = makeMempoolEntry("event2", "data2", Set(makeStateKey("addr2")))
      entry3 = makeMempoolEntry("event3", "data3", Set(makeStateKey("addr3")))
      _ <- mempool.addEntry(entry1)
      _ <- mempool.addEntry(entry2)
      _ <- mempool.addEntry(entry3)

      sizeBefore <- mempool.size

      // Simulate consensus: events 1 and 2 were included, event 3 was returned
      includedHashes = Set(entry1.hashed.hash, entry2.hashed.hash)
      _ <- mempool.clearIncluded(includedHashes)

      sizeAfter <- mempool.size
      remaining <- mempool.snapshot()

    } yield
      expect.all(
        sizeBefore == 3,
        sizeAfter == 1,
        remaining.hashes.contains(entry3.hashed.hash),
        !remaining.hashes.contains(entry1.hashed.hash),
        !remaining.hashes.contains(entry2.hashed.hash)
      )
  }

  test("consensus flow: full proposal building simulation") {
    for {
      mempool <- makeTestMempool

      // Setup: Add events to mempool (simulating gossip arrival)
      entry1 = makeMempoolEntry("tx1", "transfer", Set(makeStateKey("sender1"), makeStateKey("receiver1")))
      entry2 = makeMempoolEntry("tx2", "transfer", Set(makeStateKey("sender2"), makeStateKey("receiver2")))
      entry3 = makeMempoolEntry("tx3", "transfer", Set(makeStateKey("sender3"), makeStateKey("receiver3")))
      _ <- mempool.addEntry(entry1)
      _ <- mempool.addEntry(entry2)
      _ <- mempool.addEntry(entry3)

      // Step 1: Pull events from mempool (as in buildProposalTransition)
      mempoolData <- mempool.snapshot().map { snap =>
        val events = snap.events.map(_.signed.value).toSet
        val valueToHash = snap.events.map(h => h.signed.value -> h.hash).toMap
        (events, valueToHash)
      }
      (mempoolEvents, mempoolValueToHash) = mempoolData

      // Step 2: Combine with gossip events (empty in this test)
      gossipEvents = Set.empty[TestEvent]
      allEvents = gossipEvents ++ mempoolEvents

      // Step 3: Simulate createArtifact - some events returned (not included)
      // In real flow, this would be events that didn't fit in the snapshot
      returnedEvents = Set(TestEvent("tx3", "transfer")) // tx3 was returned (not included)

      // Step 4: Calculate included events and clear from mempool
      includedEvents = mempoolEvents -- returnedEvents
      includedHashes = includedEvents.flatMap(mempoolValueToHash.get)
      _ <- mempool.clearIncluded(includedHashes)

      // Verify final state
      finalSnapshot <- mempool.snapshot()

    } yield
      expect.all(
        allEvents.size == 3,
        includedEvents.size == 2,
        includedHashes.size == 2,
        finalSnapshot.size == 1,
        finalSnapshot.events.head.signed.value == TestEvent("tx3", "transfer")
      )
  }

  test("consensus flow: empty mempool returns empty snapshot") {
    for {
      mempool <- makeTestMempool
      snapshot <- mempool.snapshot()
    } yield
      expect.all(
        snapshot.size == 0,
        snapshot.events.isEmpty,
        snapshot.hashes.isEmpty
      )
  }

  test("consensus flow: getMultiple retrieves events by hash for validation") {
    for {
      mempool <- makeTestMempool

      // Add events
      entry1 = makeMempoolEntry("event1", "data1", Set(makeStateKey("addr1")))
      entry2 = makeMempoolEntry("event2", "data2", Set(makeStateKey("addr2")))
      entry3 = makeMempoolEntry("event3", "data3", Set(makeStateKey("addr3")))
      _ <- mempool.addEntry(entry1)
      _ <- mempool.addEntry(entry2)
      _ <- mempool.addEntry(entry3)

      // Retrieve specific events by hash (as done during consensus validation)
      requestedHashes = Set(entry1.hashed.hash, entry3.hashed.hash)
      retrieved <- mempool.getMultiple(requestedHashes)

    } yield
      expect.all(
        retrieved.size == 2,
        retrieved.contains(entry1.hashed.hash),
        retrieved.contains(entry3.hashed.hash),
        !retrieved.contains(entry2.hashed.hash)
      )
  }

  test("consensus flow: idempotent clearIncluded") {
    for {
      mempool <- makeTestMempool

      // Add events
      entry1 = makeMempoolEntry("event1", "data1", Set(makeStateKey("addr1")))
      _ <- mempool.addEntry(entry1)

      // Clear twice with same hash (should be idempotent)
      _ <- mempool.clearIncluded(Set(entry1.hashed.hash))
      _ <- mempool.clearIncluded(Set(entry1.hashed.hash))

      finalSize <- mempool.size

    } yield expect(finalSize == 0)
  }
}
