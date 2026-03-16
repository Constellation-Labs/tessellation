package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet
import cats.effect.IO

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.ID.Id._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.peer.PeerId._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite

object TrailingCommonAncestorFilterSuite extends SimpleIOSuite {

  // Test helpers
  private def pid(name: String): PeerId =
    PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  private def proof(peerId: PeerId): SignatureProof =
    SignatureProof(peerId.toId, Signature(Hex("deadbeef")))

  private def signed[A](value: A, signers: Set[PeerId]): Signed[A] = {
    val proofs = NonEmptySet.fromSetUnsafe(SortedSet.from(signers.map(proof)))
    Signed(value, proofs)
  }

  private val peer1 = pid("peer1")
  private val peer2 = pid("peer2")
  private val peer3 = pid("peer3")
  private val peer4 = pid("peer4")
  private val peer5 = pid("peer5")

  // Simple test value type
  case class TestSnapshot(id: Int)

  private def makeStorage(snapshots: Map[SnapshotOrdinal, Signed[TestSnapshot]]): SnapshotOrdinal => IO[Option[Signed[TestSnapshot]]] =
    ordinal => IO.pure(snapshots.get(ordinal))

  // === Graceful degradation tests ===

  test("returns None when ordinal is too low for lookback window") {
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      _ => IO.pure(None),
      lookbackWindow = 5,
      minParticipation = 2
    )
    // Ordinal 1: can only produce ordinal 0 as target (1 < minParticipation=2)
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(1L)).map { result =>
      expect.same(None, result)
    }
  }

  test("returns None when snapshots are missing from storage") {
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      _ => IO.pure(None), // storage returns nothing
      lookbackWindow = 5,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      expect.same(None, result)
    }
  }

  test("returns None when fewer snapshots available than minParticipation") {
    // Only 1 snapshot available out of 5 requested, with minParticipation=2
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer2))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      expect.same(None, result)
    }
  }

  // === Degraded peer exclusion tests ===

  test("identifies degraded peer who signed fewer than threshold") {
    // peer1 signs all 3, peer2 signs 2, peer3 signs only 1 (degraded)
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1, peer2, peer3)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 3,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      // peer1 (3 >= 2) OK, peer2 (2 >= 2) OK, peer3 (1 < 2) DEGRADED
      expect.same(Some(Set(peer3)), result)
    }
  }

  test("excludes multiple degraded peers below threshold") {
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(95L) -> signed(TestSnapshot(95), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(96L) -> signed(TestSnapshot(96), Set(peer1, peer3)),
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer3)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer2))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 3
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      // peer1: 5 >= 3 OK, peer2: 3 >= 3 OK, peer3: 2 < 3 DEGRADED
      expect.same(Some(Set(peer3)), result)
    }
  }

  test("returns empty set when all peers qualify (no one degraded)") {
    val allPeers = Set(peer1, peer2, peer3)
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), allPeers),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), allPeers)
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 2,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      // All peers signed 2 >= 2, none degraded
      expect.same(Some(Set.empty[PeerId]), result)
    }
  }

  test("all visible peers degraded when none meet threshold") {
    // All peers only sign 1 snapshot each, but minParticipation=3
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer2)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer3))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 3,
      minParticipation = 3
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      // All peers appeared but below threshold → all degraded
      expect.same(Some(Set(peer1, peer2, peer3)), result)
    }
  }

  // === New peer onboarding test ===

  test("new peers with zero appearances are NOT flagged as degraded") {
    // peer1 and peer2 are active, peer3 signed once (degraded)
    // peer4 and peer5 never appear in any snapshot — they are new joiners
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1, peer2, peer3)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer2))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 3,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      // peer3 (1 < 2) is degraded. peer4, peer5 have 0 appearances → NOT in the map → NOT degraded.
      expect.same(Some(Set(peer3)), result).and(expect(!result.exists(_.contains(peer4)))).and(expect(!result.exists(_.contains(peer5))))
    }
  }

  // === Determinism test ===

  test("same inputs produce same outputs (deterministic)") {
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1, peer2, peer3)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer2, peer4)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer3, peer5))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 3,
      minParticipation = 2
    )
    for {
      result1 <- filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L))
      result2 <- filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L))
    } yield expect.same(result1, result2)
  }

  // === Partial availability test ===

  test("handles mix of available and missing snapshots") {
    // 3 of 5 snapshots available, minParticipation=2
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(96L) -> signed(TestSnapshot(96), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer3))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      // peer1: 3 >= 2 OK, peer2: 2 >= 2 OK, peer3: 1 < 2 DEGRADED
      expect.same(Some(Set(peer3)), result)
    }
  }

  // === Edge case: lookbackWindow=1, minParticipation=1 ===

  test("works with lookbackWindow=1 and minParticipation=1") {
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer2))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 1,
      minParticipation = 1
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      // Both signed 1 >= 1, none degraded
      expect.same(Some(Set.empty[PeerId]), result)
    }
  }
}
