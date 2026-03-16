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

  test("returns None when ordinal is too low for full lookback window") {
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      _ => IO.pure(None),
      lookbackWindow = 5,
      minParticipation = 2
    )
    // Ordinal 3: can only produce 3 target ordinals (3 < lookbackWindow=5)
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(3L)).map { result =>
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

  test("returns None when early region is empty (lookbackWindow == minParticipation)") {
    // With lookbackWindow=2, minParticipation=2: all snapshots are "recent", no early region
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 2,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      expect.same(None, result)
    }
  }

  // === Early/recent split degradation tests ===

  test("identifies peer that signed early but not recent as degraded") {
    // lookbackWindow=5, minParticipation=2 → early=[95,96,97], recent=[98,99]
    // peer3 signs early (95,96) but NOT recent (98,99) → degraded
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(95L) -> signed(TestSnapshot(95), Set(peer1, peer2, peer3)),
      SnapshotOrdinal.unsafeApply(96L) -> signed(TestSnapshot(96), Set(peer1, peer2, peer3)),
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer2))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      expect.same(Some(Set(peer3)), result)
    }
  }

  test("multiple peers degraded when they all dropped from recent") {
    // peer2 and peer3 sign early but not recent → both degraded
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(95L) -> signed(TestSnapshot(95), Set(peer1, peer2, peer3)),
      SnapshotOrdinal.unsafeApply(96L) -> signed(TestSnapshot(96), Set(peer1, peer2, peer3)),
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      expect.same(Some(Set(peer2, peer3)), result)
    }
  }

  test("returns empty set when no peers are degraded (all still active in recent)") {
    val allPeers = Set(peer1, peer2, peer3)
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(95L) -> signed(TestSnapshot(95), allPeers),
      SnapshotOrdinal.unsafeApply(96L) -> signed(TestSnapshot(96), allPeers),
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), allPeers),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), allPeers),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), allPeers)
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      expect.same(Some(Set.empty[PeerId]), result)
    }
  }

  // === New peer onboarding tests ===

  test("new peer appearing only in recent snapshots is NOT degraded") {
    // peer3 only appears in recent region (just joined) → not in earlySigners → not degraded
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(95L) -> signed(TestSnapshot(95), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(96L) -> signed(TestSnapshot(96), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer2, peer3)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer2, peer3))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      // peer3 only in recent → NOT degraded. No one in early dropped from recent.
      expect.same(Some(Set.empty[PeerId]), result)
    }
  }

  test("new peers with zero appearances anywhere are NOT degraded") {
    // peer4, peer5 don't appear in any snapshot — they are new joiners not in the map at all
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(95L) -> signed(TestSnapshot(95), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(96L) -> signed(TestSnapshot(96), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer2))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      // No degraded peers. peer4/peer5 are not in any snapshot → not in earlySigners → can't be degraded
      expect
        .same(Some(Set.empty[PeerId]), result)
        .and(expect(!result.exists(_.contains(peer4))))
        .and(expect(!result.exists(_.contains(peer5))))
    }
  }

  // === Post-rollback scenario (the exact bug this fixes) ===

  test("post-rollback: peers that joined after solo period are NOT degraded") {
    // Solo node (peer1) running for ordinals 95-98, then peer2+peer3 join at 99
    // lookbackWindow=5, minParticipation=2 → early=[95,96,97], recent=[98,99]
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(95L) -> signed(TestSnapshot(95), Set(peer1)),
      SnapshotOrdinal.unsafeApply(96L) -> signed(TestSnapshot(96), Set(peer1)),
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer2, peer3))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      // earlySigners = {peer1}, recentSigners = {peer1, peer2, peer3}
      // degraded = {peer1} -- {peer1, peer2, peer3} = {} (empty)
      // peer2, peer3 only in recent → not flagged
      expect.same(Some(Set.empty[PeerId]), result)
    }
  }

  // === Determinism test ===

  test("same inputs produce same outputs (deterministic)") {
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(95L) -> signed(TestSnapshot(95), Set(peer1, peer2, peer3)),
      SnapshotOrdinal.unsafeApply(96L) -> signed(TestSnapshot(96), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1, peer3)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer2, peer4)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer3, peer5))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 2
    )
    for {
      result1 <- filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L))
      result2 <- filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L))
    } yield expect.same(result1, result2)
  }

  // === Partial availability test ===

  test("handles mix of available and missing snapshots with early/recent split") {
    // 3 of 5 snapshots available: ordinals 96, 98, 99
    // Sorted: [96, 98, 99]. minParticipation=2 → recent=[98,99], early=[96]
    // peer3 signs 96 (early) but not 98,99 (recent) → degraded
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(96L) -> signed(TestSnapshot(96), Set(peer1, peer2, peer3)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer2))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      expect.same(Some(Set(peer3)), result)
    }
  }

  // === Edge case: peer returns in recent after gap ===

  test("peer that was early AND recent is NOT degraded even if absent in middle") {
    // peer2 signs early (95,96), absent middle (97), returns in recent (98,99) → NOT degraded
    val snapshots = Map(
      SnapshotOrdinal.unsafeApply(95L) -> signed(TestSnapshot(95), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(96L) -> signed(TestSnapshot(96), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(97L) -> signed(TestSnapshot(97), Set(peer1)),
      SnapshotOrdinal.unsafeApply(98L) -> signed(TestSnapshot(98), Set(peer1, peer2)),
      SnapshotOrdinal.unsafeApply(99L) -> signed(TestSnapshot(99), Set(peer1, peer2))
    )
    val filter = TrailingCommonAncestorFilter.make[IO, TestSnapshot](
      makeStorage(snapshots),
      lookbackWindow = 5,
      minParticipation = 2
    )
    filter.degradedPeers(SnapshotOrdinal.unsafeApply(100L)).map { result =>
      // earlySigners={peer1,peer2}, recentSigners={peer1,peer2}
      // degraded = {} → peer2 returned, not degraded
      expect.same(Some(Set.empty[PeerId]), result)
    }
  }
}
