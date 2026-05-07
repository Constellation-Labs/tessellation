package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{Candidates, ConsensusStateUpdater}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, TimeTrigger}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import io.circe.parser.decode
import io.circe.syntax._
import weaver.FunSuite

/** Coverage for the v13 (2026-05-07) `Facility.appliedEvictionCerts` schema addition and the
  * `identifyForkedPeersByAppliedCerts` helper that gates round advancement on quorum-many
  * Facilities agreeing on the same applied-cert SET.
  *
  * See `docs/consensus/eviction-cert-deterministic-shrinkage.md` for the design context.
  */
object AppliedEvictionCertSuite extends FunSuite {

  private val facHash: Hash = Hash.fromBytes("FAC".getBytes("UTF-8"))
  private val lastSnap: Hash = Hash.fromBytes("LAST".getBytes("UTF-8"))
  private val targetA: PeerId = PeerId(Hex("aa" * 64))
  private val targetB: PeerId = PeerId(Hex("bb" * 64))
  private val targetC: PeerId = PeerId(Hex("cc" * 64))
  private val peer1: PeerId = PeerId(Hex("11" * 64))
  private val peer2: PeerId = PeerId(Hex("22" * 64))
  private val peer3: PeerId = PeerId(Hex("33" * 64))
  private val peer4: PeerId = PeerId(Hex("44" * 64))
  private val peer5: PeerId = PeerId(Hex("55" * 64))

  private def dummyProof(tag: String): SignatureProof =
    SignatureProof(Id(Hex(tag.getBytes("UTF-8").map(b => f"$b%02x").mkString)), Signature(Hex("00")))

  private def vote(target: PeerId, proofTag: String): Signed[EvictionVote] =
    Signed(
      EvictionVote(
        targetPeer = target,
        reason = EvictionReason.Silent,
        facilitatorsHash = facHash,
        lastSnapshotHash = lastSnap
      ),
      NonEmptySet.of(dummyProof(proofTag))
    )

  private def cert(target: PeerId): EvictionCertificate =
    EvictionCertificate(
      targetPeer = target,
      reason = EvictionReason.Silent,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      votes = NonEmptySet.of(
        vote(target, s"v1-${target.value.value.take(2)}"),
        vote(target, s"v2-${target.value.value.take(2)}"),
        vote(target, s"v3-${target.value.value.take(2)}")
      )
    )

  private def facility(applied: List[EvictionCertificate], trigger: Option[ConsensusTrigger] = TimeTrigger.some): Facility =
    Facility(
      eventHashes = Set.empty,
      candidates = Candidates(Set.empty),
      trigger = trigger,
      facilitatorsHash = facHash,
      lastGlobalSnapshotOrdinal = SnapshotOrdinal.MinValue,
      lastSnapshotHash = lastSnap,
      consensusConfigHash = none,
      appliedEvictionCerts = applied
    )

  // === Schema / codec regression ===

  test("Facility round-trips through JSON with empty appliedEvictionCerts (no-regression for pre-v13 wire format)") {
    val f = facility(applied = List.empty)
    val json = f.asJson
    val roundTripped = decode[Facility](json.noSpaces)
    expect(roundTripped.exists(_ === f), s"empty-cert Facility must round-trip; got $roundTripped")
  }

  test("Facility round-trips through JSON with one applied cert") {
    val f = facility(applied = List(cert(targetA)))
    val json = f.asJson
    val roundTripped = decode[Facility](json.noSpaces)
    expect(roundTripped.exists(_ === f), s"single-cert Facility must round-trip; got $roundTripped")
  }

  test("Facility round-trips through JSON with multiple applied certs (sorted by EvictionCertificate.ordering)") {
    val sorted = List(cert(targetA), cert(targetB), cert(targetC)).sorted
    val f = facility(applied = sorted)
    val json = f.asJson
    val roundTripped = decode[Facility](json.noSpaces)
    expect(roundTripped.exists(_ === f), s"multi-cert Facility must round-trip; got $roundTripped")
  }

  test("Facility's `appliedEvictionCerts` field is required on the wire post-v13 (consensusConfigHash gates mixed clusters)") {
    // The Scala-side default `= List.empty` only matters for direct construction. The derevo/circe
    // derivation does NOT honor case-class defaults at decode time — old wire form lacking the field
    // would fail to decode. This is intentional and gated at the cluster level by the
    // `consensusSchemaVersion` bump from 12 to 13 (`deterministicConfigHash`): a v12 peer's Facility
    // would be rejected via `checkForkByConsensusConfigHash` before any decode mismatch surfaced.
    // Test pinned here so future schema changes that try to bypass the hash bump trip immediately.
    val v13 = facility(applied = List.empty).asJson.noSpaces
    io.circe.parser.parse(v13).fold(
      e => failure(s"could not parse v13 JSON: $e"),
      json =>
        json.hcursor.downField("appliedEvictionCerts").delete.top match {
          case Some(stripped) =>
            decode[Facility](stripped.noSpaces) match {
              case Right(_) =>
                failure("stripped-field JSON unexpectedly decoded — derivation behavior changed; coordinate with consensusConfigHash bump")
              case Left(_) =>
                success
            }
          case None => failure("could not strip appliedEvictionCerts field")
        }
    )
  }

  // === identifyForkedPeersByAppliedCerts: determinism + minority-eviction ===

  test("identifyForkedPeersByAppliedCerts: empty observations → empty set (no eviction)") {
    val result = ConsensusStateUpdater.identifyForkedPeersByAppliedCerts(
      ownAppliedCertTargets = List.empty,
      observations = SortedMap.empty[PeerId, List[String]]
    )
    expect(result.isEmpty, s"empty observations must produce no evictions; got $result")
  }

  test("identifyForkedPeersByAppliedCerts: no certs anywhere matches own empty list (no-regression baseline)") {
    val result = ConsensusStateUpdater.identifyForkedPeersByAppliedCerts(
      ownAppliedCertTargets = List.empty,
      observations = SortedMap(peer1 -> List.empty, peer2 -> List.empty, peer3 -> List.empty)
    )
    expect(result.isEmpty, s"all-empty quorum matches own empty; got $result")
  }

  test("identifyForkedPeersByAppliedCerts: self in majority with cert {A} evicts minority with {}") {
    val targets = List(targetA.value.value)
    val result = ConsensusStateUpdater.identifyForkedPeersByAppliedCerts(
      ownAppliedCertTargets = targets,
      observations = SortedMap(
        peer1 -> targets,    // same as self
        peer2 -> targets,    // same as self
        peer3 -> targets,    // same as self (3 with cert)
        peer4 -> List.empty, // minority — no cert
        peer5 -> List.empty  // minority — no cert
      )
    )
    expect(result === Set(peer4, peer5), s"minority-no-cert peers must be evicted; got $result")
  }

  test("identifyForkedPeersByAppliedCerts: self in MINORITY with cert {A} returns empty (round stalls)") {
    val targets = List(targetA.value.value)
    val result = ConsensusStateUpdater.identifyForkedPeersByAppliedCerts(
      ownAppliedCertTargets = targets,
      observations = SortedMap(
        peer1 -> targets,    // self has cert
        peer2 -> List.empty, // majority — no cert
        peer3 -> List.empty, // majority — no cert
        peer4 -> List.empty, // majority — no cert
        peer5 -> List.empty  // majority — no cert
      )
    )
    expect(result.isEmpty, s"self-in-minority must NOT evict (recovery path handles this); got $result")
  }

  test("identifyForkedPeersByAppliedCerts: tied 2/2 split — pickMajority breaks ties by element Order, mirroring identifyForkedPeers") {
    // pickMajority returns the value with max count; in ties, the maximum-by-element-order wins.
    // For List[String], "aa..." > "" lexicographically, so the cert-targets list wins ties.
    // This mirrors how identifyForkedPeers resolves Hash ties (whichever Hash sorts highest).
    // The resulting eviction depends on which side self is on:
    //   - self on the cert side → evicts no-cert peers
    //   - self on the no-cert side → returns empty (recovery path handles)
    val targets = List(targetA.value.value)
    val resultSelfOnCertSide = ConsensusStateUpdater.identifyForkedPeersByAppliedCerts(
      ownAppliedCertTargets = targets,
      observations = SortedMap(peer1 -> targets, peer2 -> targets, peer3 -> List.empty, peer4 -> List.empty)
    )
    val resultSelfOnNoCertSide = ConsensusStateUpdater.identifyForkedPeersByAppliedCerts(
      ownAppliedCertTargets = List.empty,
      observations = SortedMap(peer1 -> targets, peer2 -> targets, peer3 -> List.empty, peer4 -> List.empty)
    )
    expect(resultSelfOnCertSide === Set(peer3, peer4), s"self-on-tie-winner side evicts losers; got $resultSelfOnCertSide").and(
      expect(resultSelfOnNoCertSide.isEmpty, s"self-on-tie-loser side does NOT evict (recovery handles); got $resultSelfOnNoCertSide")
    )
  }

  test("identifyForkedPeersByAppliedCerts: cert ORDER does not matter — set semantics enforced via sorted lists") {
    // Caller is responsible for sorting before calling. This test confirms that two equivalent
    // sorted lists compare equal (different orderings of the same set would compare unequal,
    // which is why the Creator and the Advancer both sort by peer-id value).
    val sorted1 = List(targetA, targetB, targetC).map(_.value.value).sorted
    val sorted2 = List(targetC, targetA, targetB).map(_.value.value).sorted
    expect(sorted1 === sorted2, s"two sorted lists of the same set must compare equal; $sorted1 vs $sorted2")
  }

  // === Determinism property: two nodes with same applied-cert SET produce identical Facility wire form ===

  test("DETERMINISM: two nodes with same applied-cert SET produce byte-identical Facility JSON serialization") {
    // Independent of the order in which each node assembled or received the certs, the canonical
    // sort by EvictionCertificate.ordering (target peer id) ensures both nodes serialize to the
    // exact same bytes — which is the invariant `identifyForkedPeersByAppliedCerts` relies on.
    val nodeAList = List(cert(targetB), cert(targetA), cert(targetC)).sorted
    val nodeBList = List(cert(targetC), cert(targetB), cert(targetA)).sorted
    val fA = facility(applied = nodeAList)
    val fB = facility(applied = nodeBList)
    val jsonA = fA.asJson.noSpaces
    val jsonB = fB.asJson.noSpaces
    expect(jsonA === jsonB, s"sorted-canonical lists must produce identical wire form; A=${jsonA.take(80)}... B=${jsonB.take(80)}...")
  }

  test("DETERMINISM: applied-cert canonical-target list (used by identifyForkedPeersByAppliedCerts) is stable across re-orderings") {
    val canonical = (f: Facility) => f.appliedEvictionCerts.map(_.targetPeer.value.value).sorted
    val fA = facility(applied = List(cert(targetB), cert(targetA), cert(targetC)).sorted)
    val fB = facility(applied = List(cert(targetC), cert(targetB), cert(targetA)).sorted)
    expect(canonical(fA) === canonical(fB), s"canonical target list must match; ${canonical(fA)} vs ${canonical(fB)}")
  }
}
