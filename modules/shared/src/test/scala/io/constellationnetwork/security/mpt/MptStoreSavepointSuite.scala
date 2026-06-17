package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.mpt.{GlobalStateFieldId, GlobalStateKey, MptStore}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.producer.InMemoryMerklePatriciaProducer
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object MptStoreSavepointSuite extends MutableIOSuite {

  type Res = (HasherSelector[IO], JsonSerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit kryo =>
      JsonSerializer.forAsync[IO].asResource.map { implicit json =>
        (
          HasherSelector.forSync[IO](
            Hasher.forJson[IO],
            Hasher.forKryo[IO],
            hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }
          ),
          json
        )
      }
    }

  test("savepoint captures and restores producer state correctly") { implicit res =>
    implicit val (hs, js) = res
    hs.withCurrent { implicit hasher =>
      for {
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        key1 <- hasher.hash("key1").map(hash => Hex(hash.value))
        key2 <- hasher.hash("key2").map(hash => Hex(hash.value))

        // Insert initial data
        _ <- producer.insert(Map(key1 -> "value1"))
        entriesBefore <- producer.entries

        // Take savepoint
        sp <- producer.savepoint

        // Insert more data (simulating validation mutation)
        _ <- producer.insert(Map(key2 -> "value2"))
        entriesAfterMutation <- producer.entries

        // Restore savepoint
        _ <- sp.restore
        entriesAfterRestore <- producer.entries

      } yield
        expect.all(
          entriesBefore.size == 1,
          entriesAfterMutation.size == 2,
          entriesAfterRestore.size == 1,
          entriesAfterRestore.contains(key1),
          !entriesAfterRestore.contains(key2)
        )
    }
  }

  test("savepoint restores trie and rootHash correctly") { implicit res =>
    implicit val (hs, js) = res
    hs.withCurrent { implicit hasher =>
      for {
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        key1 <- hasher.hash("key1").map(hash => Hex(hash.value))
        key2 <- hasher.hash("key2").map(hash => Hex(hash.value))
        ordinal1 = SnapshotOrdinal.unsafeApply(100L)
        ordinal2 = SnapshotOrdinal.unsafeApply(101L)

        // Insert and build trie
        _ <- producer.insert(Map(key1 -> "value1"))
        trie1 <- producer.buildForOrdinal(ordinal1)
        rootHash1 = trie1.map(_.rootHash)

        // Take savepoint AFTER the build
        sp <- producer.savepoint

        // Mutate: insert more, build again
        _ <- producer.insert(Map(key2 -> "value2"))
        trie2 <- producer.buildForOrdinal(ordinal2)
        rootHash2 = trie2.map(_.rootHash)

        // Restore
        _ <- sp.restore
        trie3 <- producer.buildForOrdinal(ordinal1)
        rootHash3 = trie3.map(_.rootHash)

      } yield
        expect.all(
          rootHash1.isRight,
          rootHash2.isRight,
          rootHash3.isRight,
          // After restore, root hash should match the original
          rootHash1 == rootHash3,
          // The mutated root hash should be different
          rootHash1 != rootHash2
        )
    }
  }

  test("multiple savepoints are independent") { implicit res =>
    implicit val (hs, js) = res
    hs.withCurrent { implicit hasher =>
      for {
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        key1 <- hasher.hash("key1").map(hash => Hex(hash.value))
        key2 <- hasher.hash("key2").map(hash => Hex(hash.value))
        key3 <- hasher.hash("key3").map(hash => Hex(hash.value))

        // State: {key1}
        _ <- producer.insert(Map(key1 -> "value1"))
        sp1 <- producer.savepoint

        // State: {key1, key2}
        _ <- producer.insert(Map(key2 -> "value2"))
        sp2 <- producer.savepoint

        // State: {key1, key2, key3}
        _ <- producer.insert(Map(key3 -> "value3"))
        entriesFull <- producer.entries

        // Restore sp1 → should go back to {key1}
        _ <- sp1.restore
        entriesAfterSp1 <- producer.entries

      } yield
        expect.all(
          entriesFull.size == 3,
          entriesAfterSp1.size == 1,
          entriesAfterSp1.contains(key1),
          !entriesAfterSp1.contains(key2),
          !entriesAfterSp1.contains(key3)
        )
    }
  }

  test("MptStore savepoint captures and restores store-level state") { implicit res =>
    implicit val (hs, js) = res
    implicit val hasher: Hasher[IO] = Hasher.forJson[IO]
    for {
      producer <- InMemoryMerklePatriciaProducer.make[IO]()
      store <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])

      key1 = GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw"))
      key2 = GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"))

      // Insert initial data
      _ <- store.insert(key1, "value1")
      before <- store.get[String](key1)

      // Take savepoint
      sp <- store.savepoint

      // Insert more data (simulating validation mutation)
      _ <- store.insert(key2, "value2")
      afterMutation1 <- store.get[String](key1)
      afterMutation2 <- store.get[String](key2)

      // Restore savepoint
      _ <- sp.restore
      afterRestore1 <- store.get[String](key1)
      afterRestore2 <- store.get[String](key2)

    } yield
      expect.all(
        before.contains("value1"),
        afterMutation1.contains("value1"),
        afterMutation2.contains("value2"),
        afterRestore1.contains("value1"),
        afterRestore2.isEmpty // key2 should be gone after restore
      )
  }

  // --- M1a: syncFullIfNeeded content-aware skip (commit fa0f3616d) ---
  // The ordinal tag alone can be stale: an abandoned-round mutation or a savepoint restore can
  // leave the in-memory entry set divergent while lastSyncedOrdinalRef still names the ordinal,
  // so a pure ordinal no-op would build a proposal/proof off forked state. These pin the guard.

  test("syncFullIfNeeded: tag matches but root diverged -> forces a resync to the canonical root") { implicit res =>
    implicit val (hs, js) = res
    implicit val hasher: Hasher[IO] = Hasher.forJson[IO]
    val ord = SnapshotOrdinal.unsafeApply(200L)
    val key1 = GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw"))
    val key2 = GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"))
    val stateA = Map[GlobalStateKey, String](key1 -> "value1", key2 -> "value2")
    for {
      producer <- InMemoryMerklePatriciaProducer.make[IO]()
      store <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
      _ <- store.syncFull(stateA, ord)
      expectedRoot <- store.build(ord).map(_.toOption.map(_.rootHash.value))
      // Diverge the in-memory state; the lastSynced tag still names `ord`.
      _ <- store.insert(key2, "MUTATED")
      rootAfterMutation <- store.build(ord).map(_.toOption.map(_.rootHash.value))
      // Guarded sync at the same ordinal: must verify the root, see the mismatch, and resync.
      _ <- store.syncFullIfNeeded(IO.pure(stateA), ord, expectedRoot)
      rootAfterGuard <- store.build(ord).map(_.toOption.map(_.rootHash.value))
      key2After <- store.get[String](key2)
    } yield
      expect.all(
        rootAfterMutation != expectedRoot, // the mutation really diverged the root
        rootAfterGuard == expectedRoot, // the guard forced a resync to the canonical root
        key2After.contains("value2") // canonical value restored, not "MUTATED"
      )
  }

  test("syncFullIfNeeded: tag matches and root verified -> no-op (newState is never forced)") { implicit res =>
    implicit val (hs, js) = res
    implicit val hasher: Hasher[IO] = Hasher.forJson[IO]
    val ord = SnapshotOrdinal.unsafeApply(200L)
    val key1 = GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw"))
    val key2 = GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"))
    val stateA = Map[GlobalStateKey, String](key1 -> "value1", key2 -> "value2")
    for {
      producer <- InMemoryMerklePatriciaProducer.make[IO]()
      store <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
      _ <- store.syncFull(stateA, ord)
      expectedRoot <- store.build(ord).map(_.toOption.map(_.rootHash.value))
      // newState would WIPE the store if forced; a verified no-op must not force it.
      _ <- store.syncFullIfNeeded(IO.pure(Map.empty[GlobalStateKey, String]), ord, expectedRoot)
      key1After <- store.get[String](key1)
      key2After <- store.get[String](key2)
    } yield expect.all(key1After.contains("value1"), key2After.contains("value2"))
  }

  test("syncFullIfNeeded: expectedRoot=None is a pure ordinal no-op (stale state NOT corrected)") { implicit res =>
    implicit val (hs, js) = res
    implicit val hasher: Hasher[IO] = Hasher.forJson[IO]
    val ord = SnapshotOrdinal.unsafeApply(200L)
    val key1 = GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw"))
    val key2 = GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"))
    val stateA = Map[GlobalStateKey, String](key1 -> "value1", key2 -> "value2")
    for {
      producer <- InMemoryMerklePatriciaProducer.make[IO]()
      store <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
      _ <- store.syncFull(stateA, ord)
      _ <- store.insert(key2, "MUTATED")
      _ <- store.syncFullIfNeeded(IO.pure(stateA), ord, None)
      key2After <- store.get[String](key2)
    } yield expect(key2After.contains("MUTATED")) // back-compat: None stays a pure ordinal no-op
  }

  test("syncFullIfNeeded: a new ordinal always triggers a full sync") { implicit res =>
    implicit val (hs, js) = res
    implicit val hasher: Hasher[IO] = Hasher.forJson[IO]
    val ord = SnapshotOrdinal.unsafeApply(200L)
    val ordNext = SnapshotOrdinal.unsafeApply(201L)
    val key1 = GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw"))
    val key2 = GlobalStateKey.hypergraph(GlobalStateFieldId.Balances, Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"))
    val stateA = Map[GlobalStateKey, String](key1 -> "value1", key2 -> "value2")
    val stateB = Map[GlobalStateKey, String](key1 -> "B1")
    for {
      producer <- InMemoryMerklePatriciaProducer.make[IO]()
      store <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
      _ <- store.syncFull(stateA, ord)
      _ <- store.syncFullIfNeeded(IO.pure(stateB), ordNext, None)
      key1After <- store.get[String](key1)
      key2After <- store.get[String](key2)
    } yield expect.all(key1After.contains("B1"), key2After.isEmpty) // resynced to stateB
  }
}
