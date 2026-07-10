package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.IO

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.peer.PeerId._
import io.constellationnetwork.security.hex.Hex

import weaver.SimpleIOSuite

object TrailingCommonAncestorFilterSuite extends SimpleIOSuite {

  // Test helpers
  private def pid(name: String): PeerId =
    PeerId(Hex(name.getBytes("UTF-8").map(b => f"$b%02x").mkString))

  private val peer1 = pid("peer1")
  private val peer2 = pid("peer2")
  private val peer3 = pid("peer3")
  private val peer4 = pid("peer4")
  private val peer5 = pid("peer5")
  private val peer6 = pid("peer6")

  private val filter = TrailingCommonAncestorFilter.make[IO]

  // === Graceful degradation tests ===

  test("returns None when lastFacilitators is empty") {
    filter.degradedPeers(Set.empty, Set(peer1, peer2)).map { result =>
      expect.same(None, result)
    }
  }

  test("returns None when lastSigners is empty") {
    filter.degradedPeers(Set(peer1, peer2), Set.empty).map { result =>
      expect.same(None, result)
    }
  }

  test("returns None when both sets are empty") {
    filter.degradedPeers(Set.empty, Set.empty).map { result =>
      expect.same(None, result)
    }
  }

  // === Degradation detection tests ===

  test("identifies peer that was facilitator but did not sign as degraded") {
    // peer3 was a facilitator but didn't sign the last snapshot
    val facilitators = Set(peer1, peer2, peer3)
    val signers = Set(peer1, peer2)
    filter.degradedPeers(facilitators, signers).map { result =>
      expect.same(Some(Set(peer3)), result)
    }
  }

  test("multiple peers degraded when they all failed to sign") {
    // peer2 and peer3 were facilitators but neither signed
    val facilitators = Set(peer1, peer2, peer3)
    val signers = Set(peer1)
    filter.degradedPeers(facilitators, signers).map { result =>
      expect.same(Some(Set(peer2, peer3)), result)
    }
  }

  test("returns empty set when all facilitators signed") {
    val allPeers = Set(peer1, peer2, peer3)
    filter.degradedPeers(allPeers, allPeers).map { result =>
      expect.same(Some(Set.empty[PeerId]), result)
    }
  }

  // === New peer onboarding tests ===

  test("new peer appearing only in signers is NOT degraded") {
    // peer3 signed but wasn't in the facilitator set (joined via candidates)
    val facilitators = Set(peer1, peer2)
    val signers = Set(peer1, peer2, peer3)
    filter.degradedPeers(facilitators, signers).map { result =>
      // peer3 only in signers → NOT degraded. No one in facilitators is missing from signers.
      expect.same(Some(Set.empty[PeerId]), result)
    }
  }

  test("new peers not in either set are NOT degraded") {
    // peer4, peer5 don't appear in facilitators or signers — they are brand new joiners
    val facilitators = Set(peer1, peer2)
    val signers = Set(peer1, peer2)
    filter.degradedPeers(facilitators, signers).map { result =>
      expect
        .same(Some(Set.empty[PeerId]), result)
        .and(expect(!result.exists(_.contains(peer4))))
        .and(expect(!result.exists(_.contains(peer5))))
    }
  }

  // === Post-rollback scenario ===

  test("post-rollback: solo node as facilitator, all signed → no degraded") {
    // Solo node (peer1) was the only facilitator and signed
    val facilitators = Set(peer1)
    val signers = Set(peer1)
    filter.degradedPeers(facilitators, signers).map { result =>
      expect.same(Some(Set.empty[PeerId]), result)
    }
  }

  test("post-rollback: solo facilitator but multiple signers → no degraded") {
    // Solo node was facilitator, new joiners also signed
    val facilitators = Set(peer1)
    val signers = Set(peer1, peer2, peer3)
    filter.degradedPeers(facilitators, signers).map { result =>
      // peer1 signed → not degraded. peer2, peer3 not in facilitators → irrelevant.
      expect.same(Some(Set.empty[PeerId]), result)
    }
  }

  // === Determinism test ===

  test("same inputs produce same outputs (deterministic)") {
    val facilitators = Set(peer1, peer2, peer3, peer4)
    val signers = Set(peer1, peer3, peer5)
    for {
      result1 <- filter.degradedPeers(facilitators, signers)
      result2 <- filter.degradedPeers(facilitators, signers)
    } yield expect.same(result1, result2)
  }

  // === Self-correcting behavior ===

  test("peer that signs after being degraded in previous round is NOT degraded") {
    // Round N: peer2 didn't sign → degraded
    // Round N+1: peer2 is back as facilitator AND signed → not degraded
    val facilitatorsN = Set(peer1, peer2, peer3)
    val signersN = Set(peer1, peer3)

    val facilitatorsN1 = Set(peer1, peer2, peer3)
    val signersN1 = Set(peer1, peer2, peer3)

    for {
      degradedN <- filter.degradedPeers(facilitatorsN, signersN)
      degradedN1 <- filter.degradedPeers(facilitatorsN1, signersN1)
    } yield
      expect
        .same(Some(Set(peer2)), degradedN)
        .and(expect.same(Some(Set.empty[PeerId]), degradedN1))
  }

  // === Quorum scenario ===

  test("peers missing from quorum signatures are correctly flagged") {
    // 6 facilitators, only 4 signed (quorum met but 2 missing)
    val facilitators = Set(peer1, peer2, peer3, peer4, peer5, peer6)
    val signers = Set(peer1, peer2, peer3, peer4)
    filter.degradedPeers(facilitators, signers).map { result =>
      expect.same(Some(Set(peer5, peer6)), result)
    }
  }

  // === Only one facilitator signed (minimum quorum) ===

  test("all but one facilitator degraded when only one signed") {
    val facilitators = Set(peer1, peer2, peer3)
    val signers = Set(peer1)
    filter.degradedPeers(facilitators, signers).map { result =>
      expect.same(Some(Set(peer2, peer3)), result)
    }
  }
}
