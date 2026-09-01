package io.constellationnetwork.node.shared.infrastructure.mempool

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import derevo.circe.magnolia.encoder
import derevo.derive
import weaver.SimpleIOSuite

/** Unit tests for [[EventMempool]].
  *
  * Uses a minimal TestEvent to exercise the core invariants independently of any domain-specific event types:
  *   - Correct insert / get / remove lifecycle
  *   - Idempotent add (duplicate hash → existing entry returned, size unchanged)
  *   - Capacity enforcement (MempoolFull rejection)
  *   - Snapshot ordering
  *   - Clear semantics
  */
object EventMempoolSuite extends SimpleIOSuite {

  @derive(encoder)
  case class TestEvent(value: String)

  type TestKey = Unit

  val noopExtractor: StateKeyExtractor[IO, TestEvent, TestKey] =
    _ => Set.empty[TestKey].pure[IO]

  val defaultConfig: MempoolConfig =
    MempoolConfig(maxSize = 100)

  /** Build a [[Signed]] wrapper with a deterministic but fake signature so we can construct Signed[TestEvent] without real cryptographic
    * key material.
    */
  def fakeSignedEvent(value: String): Signed[TestEvent] = {
    val id = Id(Hex("a" * 128))
    val signature = Signature(Hex("b" * 128))
    Signed(TestEvent(value), NonEmptySet.one(SignatureProof(id, signature)))
  }

  // ── insert / lookup ─────────────────────────────────────────

  test("add returns Right for a new event") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      result <- mempool.add(fakeSignedEvent("hello"))
    } yield expect(result.isRight, s"Expected Right, got $result")
  }

  test("get returns the event after insertion") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      ev = fakeSignedEvent("lookup")
      entry <- mempool.add(ev).map(_.toOption.get)
      found <- mempool.get(entry.hashed.hash)
    } yield expect(found.isDefined, "Event should be retrievable after insertion")
  }

  test("get returns None for an unknown hash") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      found <- mempool.get(Hash("dead" * 16))
    } yield expect(found.isEmpty, "Unknown hash should return None")
  }

  // ── deduplication ────────────────────────────────────────────

  test("adding the same event twice is idempotent — size stays 1") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      ev = fakeSignedEvent("dedup-me")
      r1 <- mempool.add(ev)
      r2 <- mempool.add(ev)
      sz <- mempool.size
    } yield
      expect.all(
        r1.isRight,
        r2.isRight, // returns the existing entry without re-inserting
        sz == 1
      )
  }

  test("addWithStatus atomically distinguishes a new insertion from an idempotent delivery") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      ev = fakeSignedEvent("trigger-intent")
      first <- mempool.addWithStatus(ev)
      duplicate <- mempool.addWithStatus(ev)
    } yield
      expect.all(
        first.exists(_.inserted),
        duplicate.exists(result => !result.inserted),
        first.map(_.entry.hashed.hash) == duplicate.map(_.entry.hashed.hash)
      )
  }

  // ── capacity ─────────────────────────────────────────────────

  test("add is rejected with MempoolFull when at capacity") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      smallConfig = MempoolConfig(maxSize = 2)
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, smallConfig)
      _ <- mempool.add(fakeSignedEvent("slot-1"))
      _ <- mempool.add(fakeSignedEvent("slot-2"))
      result <- mempool.add(fakeSignedEvent("overflow"))
    } yield
      expect(
        result == Left(MempoolRejectionReason.MempoolFull),
        s"Expected MempoolFull, got $result"
      )
  }

  // ── remove ────────────────────────────────────────────────────

  test("remove decrements size") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      entry <- mempool.add(fakeSignedEvent("to-remove")).map(_.toOption.get)
      sz0 <- mempool.size
      _ <- mempool.remove(Set(entry.hashed.hash))
      sz1 <- mempool.size
    } yield expect.all(sz0 == 1, sz1 == 0)
  }

  test("size tracks multiple additions and a partial removal") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      e1 <- mempool.add(fakeSignedEvent("e1")).map(_.toOption.get)
      _ <- mempool.add(fakeSignedEvent("e2"))
      _ <- mempool.add(fakeSignedEvent("e3"))
      sz3 <- mempool.size
      _ <- mempool.remove(Set(e1.hashed.hash))
      sz2 <- mempool.size
    } yield expect.all(sz3 == 3, sz2 == 2)
  }

  // ── snapshot ──────────────────────────────────────────────────

  test("snapshot contains all inserted events") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      e1 <- mempool.add(fakeSignedEvent("snap-1")).map(_.toOption.get)
      e2 <- mempool.add(fakeSignedEvent("snap-2")).map(_.toOption.get)
      e3 <- mempool.add(fakeSignedEvent("snap-3")).map(_.toOption.get)
      snap <- mempool.snapshot(limit = 10)
      keys = snap.hashes
    } yield
      expect.all(
        keys.contains(e1.hashed.hash),
        keys.contains(e2.hashed.hash),
        keys.contains(e3.hashed.hash)
      )
  }

  test("snapshot respects the limit parameter") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      _ <- (1 to 5).toList.traverse_(i => mempool.add(fakeSignedEvent(s"ev-$i")))
      snap <- mempool.snapshot(limit = 3)
    } yield expect(snap.entries.size == 3, s"Snapshot should have 3 events, got ${snap.entries.size}")
  }

  test("deferToBack preserves an event while releasing the bounded FIFO head") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      oldest <- mempool.add(fakeSignedEvent("oldest-invalid-for-this-parent")).map(_.toOption.get)
      next <- mempool.add(fakeSignedEvent("next-valid")).map(_.toOption.get)
      _ <- mempool.add(fakeSignedEvent("newest"))
      before <- mempool.snapshot(limit = 1)
      _ <- mempool.deferToBack(Set(oldest.hashed.hash))
      after <- mempool.snapshot(limit = 1)
      retained <- mempool.get(oldest.hashed.hash)
      size <- mempool.size
    } yield
      expect.all(
        before.hashes === Set(oldest.hashed.hash),
        after.hashes === Set(next.hashed.hash),
        retained.isDefined,
        size === 3
      )
  }

  test("suspend hides events from snapshots and hash declarations until reactivated") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      active <- mempool.add(fakeSignedEvent("active")).map(_.toOption.get)
      held <- mempool.add(fakeSignedEvent("held")).map(_.toOption.get)
      _ <- mempool.suspend(Set(held.hashed.hash))
      activeSnap <- mempool.snapshot(limit = 10)
      heldSnap <- mempool.suspendedSnapshot(limit = 10)
      declaredHashes <- mempool.getEventHashes
      activeSize <- mempool.size
      stillRetrievable <- mempool.get(held.hashed.hash)
      _ <- mempool.reactivate(Set(held.hashed.hash))
      reactivatedSnap <- mempool.snapshot(limit = 10)
    } yield
      expect.all(
        activeSnap.hashes.contains(active.hashed.hash),
        !activeSnap.hashes.contains(held.hashed.hash),
        heldSnap.hashes.contains(held.hashed.hash),
        !declaredHashes.contains(held.hashed.hash),
        activeSize == 1,
        stillRetrievable.isDefined,
        reactivatedSnap.hashes.contains(held.hashed.hash)
      )
  }

  test("clearIncluded removes suspended events") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      entry <- mempool.add(fakeSignedEvent("committed-while-suspended")).map(_.toOption.get)
      _ <- mempool.suspend(Set(entry.hashed.hash))
      _ <- mempool.clearIncluded(Set(entry.hashed.hash))
      active <- mempool.snapshot(limit = 10)
      suspended <- mempool.suspendedSnapshot(limit = 10)
      found <- mempool.get(entry.hashed.hash)
      size <- mempool.size
    } yield
      expect.all(
        !active.hashes.contains(entry.hashed.hash),
        !suspended.hashes.contains(entry.hashed.hash),
        found.isEmpty,
        size == 0
      )
  }

  // ── clear ─────────────────────────────────────────────────────

  test("clear empties the mempool") {
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
      mempool <- EventMempool.make[IO, TestEvent, TestKey](noopExtractor, defaultConfig)
      _ <- mempool.add(fakeSignedEvent("a"))
      _ <- mempool.add(fakeSignedEvent("b"))
      _ <- mempool.clear
      sz <- mempool.size
    } yield expect(sz == 0, "Mempool should be empty after clear")
  }
}
