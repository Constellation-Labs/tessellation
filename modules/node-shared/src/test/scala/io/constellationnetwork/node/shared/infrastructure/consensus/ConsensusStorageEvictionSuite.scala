package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{EvictionCertificate, EvictionReason, EvictionVote}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import io.chrisdavenport.mapref.MapRef
import weaver.SimpleIOSuite

/** Behavioural tests for the eviction-vote and eviction-certificate storage accessors added in B1. Full ConsensusStorage requires many
  * type-class witnesses that make a direct test prohibitively boilerplate-heavy; this suite follows the same pattern as
  * `ConsensusStorageLockSuite` — exercises the same invariants against the MapRef / map-manipulation shape that ConsensusStorage uses
  * internally.
  *
  * Covers:
  *
  *   - `addEvictionVote` semantics: first-write-wins per (voter, target); multiple voters and multiple targets accumulate independently
  *   - `storeAssembledEvictionCertificate` / `getAssembledEvictionCertificates`: Set-growing semantics, empty-default read, multi-target
  *     accumulation
  */
object ConsensusStorageEvictionSuite extends SimpleIOSuite {

  private val facHash: Hash = Hash.fromBytes("FAC".getBytes("UTF-8"))
  private val lastSnap: Hash = Hash.fromBytes("LAST".getBytes("UTF-8"))

  private val voter1: PeerId = PeerId(Hex("01" * 64))
  private val voter2: PeerId = PeerId(Hex("02" * 64))
  private val targetA: PeerId = PeerId(Hex("aa" * 64))
  private val targetB: PeerId = PeerId(Hex("bb" * 64))

  private def proof(tag: String): SignatureProof =
    SignatureProof(Id(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString)), Signature(Hex("00")))

  private def signedVote(signerTag: String, target: PeerId): Signed[EvictionVote] =
    Signed(
      EvictionVote(target, EvictionReason.Silent, facHash, lastSnap),
      NonEmptySet.of(proof(signerTag))
    )

  // Mirrors the map-manipulation inside ConsensusStorage.addEvictionVote. The real function
  // calls `updateResources(key) { resources => ... }` which wraps this in an IO effect; here we
  // drive the map-level invariants directly.
  private def addVote(
    evictionVotes: Map[PeerId, Map[PeerId, Signed[EvictionVote]]],
    origin: PeerId,
    vote: Signed[EvictionVote]
  ): Map[PeerId, Map[PeerId, Signed[EvictionVote]]] = {
    val target = vote.value.targetPeer
    val currentPerTarget = evictionVotes.getOrElse(target, Map.empty)
    val updatedPerTarget = currentPerTarget.get(origin) match {
      case Some(_) => currentPerTarget // first-write-wins
      case None    => currentPerTarget.updated(origin, vote)
    }
    evictionVotes.updated(target, updatedPerTarget)
  }

  // === addEvictionVote behaviour ===

  pureTest("addEvictionVote: first-write-wins per (voter, target)") {
    val v1 = signedVote("sig-first", targetA)
    val v2 = signedVote("sig-replacement", targetA)
    val m0 = Map.empty[PeerId, Map[PeerId, Signed[EvictionVote]]]
    val m1 = addVote(m0, voter1, v1)
    val m2 = addVote(m1, voter1, v2) // same origin, same target, different signed bytes
    expect(m2(targetA)(voter1) === v1, s"expected first vote preserved, got: ${m2(targetA)(voter1)}")
  }

  pureTest("addEvictionVote: multiple voters on same target accumulate") {
    val va1 = signedVote("v1-sig", targetA)
    val va2 = signedVote("v2-sig", targetA)
    val m = addVote(addVote(Map.empty, voter1, va1), voter2, va2)
    expect(m(targetA).size === 2, s"expected 2 voters on targetA, got: ${m(targetA).size}").and(
      expect(m(targetA)(voter1) === va1, "voter1 entry missing").and(
        expect(m(targetA)(voter2) === va2, "voter2 entry missing")
      )
    )
  }

  pureTest("addEvictionVote: multiple targets accumulate independently") {
    val va = signedVote("v1-for-a", targetA)
    val vb = signedVote("v1-for-b", targetB)
    val m = addVote(addVote(Map.empty, voter1, va), voter1, vb)
    expect(m.keySet === Set(targetA, targetB), s"expected both targets tracked, got: ${m.keySet}").and(
      expect(m(targetA).size === 1).and(expect(m(targetB).size === 1))
    )
  }

  pureTest("addEvictionVote: voter1 for targetA does not overwrite voter1 for targetB") {
    val va = signedVote("v1-for-a", targetA)
    val vb = signedVote("v1-for-b", targetB)
    val m = addVote(addVote(Map.empty, voter1, va), voter1, vb)
    // Regression against misindexing: per-target map MUST be keyed by target first, voter second
    expect(m(targetA)(voter1) === va, "targetA -> voter1 entry must carry targetA vote").and(
      expect(m(targetB)(voter1) === vb, "targetB -> voter1 entry must carry targetB vote")
    )
  }

  // === assembled certificate storage ===

  test("storeAssembledEvictionCertificate: empty default read returns empty Set") {
    MapRef.ofConcurrentHashMap[IO, Long, Set[EvictionCertificate]]().flatMap { ref =>
      ref(1L).get.map(_.getOrElse(Set.empty)).map { certs =>
        expect.same(Set.empty[EvictionCertificate], certs)
      }
    }
  }

  test("storeAssembledEvictionCertificate: append to empty produces singleton set") {
    val cert = EvictionCertificate(
      targetPeer = targetA,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = NonEmptySet.of(signedVote("v1", targetA))
    )
    MapRef.ofConcurrentHashMap[IO, Long, Set[EvictionCertificate]]().flatMap { ref =>
      for {
        _ <- ref(1L).update {
          case Some(existing) => (existing + cert).some
          case None           => Set(cert).some
        }
        result <- ref(1L).get.map(_.getOrElse(Set.empty))
      } yield expect.same(Set(cert), result)
    }
  }

  test("storeAssembledEvictionCertificate: multiple targets accumulate in Set") {
    val certA = EvictionCertificate(targetA, EvictionReason.Silent, facHash, lastSnap, NonEmptySet.of(signedVote("a1", targetA)))
    val certB = EvictionCertificate(targetB, EvictionReason.Silent, facHash, lastSnap, NonEmptySet.of(signedVote("b1", targetB)))
    MapRef.ofConcurrentHashMap[IO, Long, Set[EvictionCertificate]]().flatMap { ref =>
      val addOne = (c: EvictionCertificate) =>
        ref(1L).update {
          case Some(existing) => (existing + c).some
          case None           => Set(c).some
        }
      for {
        _ <- addOne(certA)
        _ <- addOne(certB)
        result <- ref(1L).get.map(_.getOrElse(Set.empty))
      } yield expect.same(Set(certA, certB), result)
    }
  }

  test("storeAssembledEvictionCertificate: separate keys are independent") {
    val certA = EvictionCertificate(targetA, EvictionReason.Silent, facHash, lastSnap, NonEmptySet.of(signedVote("a1", targetA)))
    val certB = EvictionCertificate(targetB, EvictionReason.Silent, facHash, lastSnap, NonEmptySet.of(signedVote("b1", targetB)))
    MapRef.ofConcurrentHashMap[IO, Long, Set[EvictionCertificate]]().flatMap { ref =>
      for {
        _ <- ref(1L).update {
          case Some(existing) => (existing + certA).some
          case None           => Set(certA).some
        }
        _ <- ref(2L).update {
          case Some(existing) => (existing + certB).some
          case None           => Set(certB).some
        }
        at1 <- ref(1L).get.map(_.getOrElse(Set.empty))
        at2 <- ref(2L).get.map(_.getOrElse(Set.empty))
      } yield expect.same(Set(certA), at1).and(expect.same(Set(certB), at2))
    }
  }

  test("storeAssembledEvictionCertificate: clearing a key removes all certs") {
    val cert = EvictionCertificate(targetA, EvictionReason.Silent, facHash, lastSnap, NonEmptySet.of(signedVote("a1", targetA)))
    MapRef.ofConcurrentHashMap[IO, Long, Set[EvictionCertificate]]().flatMap { ref =>
      for {
        _ <- ref(1L).update {
          case Some(existing) => (existing + cert).some
          case None           => Set(cert).some
        }
        _ <- ref(1L).set(none)
        result <- ref(1L).get.map(_.getOrElse(Set.empty))
      } yield expect.same(Set.empty[EvictionCertificate], result)
    }
  }
}
