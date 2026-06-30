package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ControllerEvidenceEntry, SnapshotOrdinal}
import io.constellationnetwork.security.hex.Hex

import io.circe.Printer
import io.circe.syntax._
import weaver.SimpleIOSuite

/** Regression for the ordinal-3150166 controller-evidence nondeterminism.
  *
  * Two honest nodes finalize the SAME round, but their node-local observations differ because declaration / signature gossip accretes
  * asymmetrically: node A crosses the signature quorum having observed 3 proofs while node B has already received a strict superset (4
  * proofs, plus one extra facility declaration with a divergent facilitatorsHash that node B fork-evicted locally and node A never saw).
  * The pre-fix derivation (`roundStartFacilitators -- state.removedFacilitators`) let that local fork-eviction reach
  * `ControllerEvidenceEntry.completedSigners` / `recentSigners` / `recentProofSizes` -- the windows packed into SIGNED artifact bytes -- so
  * the two nodes' evidence windows diverged, their derived committees diverged, and proposal validation wedged on
  * `GlobalArtifactMismatch[controllerEvidenceDiffer]`.
  *
  * The fix routes the pack through `ControllerEvidenceDerivation.canonicalCompletedSigners` / `canonicalCommittee`, whose inputs are ONLY
  * round-start-frozen and quorum-accepted-proposal data (see the determinism argument on the helpers). This suite locks in:
  *
  *   - identical `ControllerEvidenceEntry` regardless of the local observation superset,
  *   - identical signed-bytes payload via the existing `signedArtifactOperationalState` helper, and
  *   - that the retired derivation really was a divergence channel on the same fixtures (negative control).
  *
  * The fixture stands in for the two StateAdvancers' round state: node-shared cannot depend on the dag-l0 / currency-l0 schema (dependency
  * direction), so it carries exactly the fields the pack sites read.
  */
object ControllerEvidenceCanonicalSignersSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId = PeerId(Hex(c.toString * 128))
  private def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)

  private val a = peer('a')
  private val b = peer('b')
  private val c = peer('c')
  private val d = peer('d')
  private val e = peer('e')
  private val f = peer('f')

  /** One node's view of the finalizing round.
    *
    * The first five fields are consensus-agreed (frozen at round creation or carried on the quorum-accepted proposal) and are therefore
    * IDENTICAL between the two fixtures. The last two are node-local observations and DIFFER -- the pack must be insensitive to them.
    */
  private final case class NodeRoundView(
    // Consensus-agreed inputs (identical across deciding nodes).
    roundStartFacilitators: SortedSet[PeerId],
    acceptedObservedResponders: Set[PeerId],
    certifiedEvictionTargets: SortedSet[PeerId],
    acceptedTimeoutCertificateVoters: SortedSet[PeerId],
    admittedFacilitators: SortedSet[PeerId],
    // Node-local observations (legitimately divergent across honest nodes).
    removedFacilitators: Set[PeerId],
    localArtifactProofSigners: SortedSet[PeerId]
  )

  // The round: committee {a..f}; f was cert-evicted by the accepted proposal; the leader
  // observed everyone but f responding.
  private val roundStart = SortedSet(a, b, c, d, e, f)
  private val responders: Set[PeerId] = Set(a, b, c, d, e)
  private val certEvicted = SortedSet(f)

  // Node A finalized the instant quorum crossed: 3 proofs observed, no fork-eviction seen.
  private val nodeA = NodeRoundView(
    roundStartFacilitators = roundStart,
    acceptedObservedResponders = responders,
    certifiedEvictionTargets = certEvicted,
    acceptedTimeoutCertificateVoters = SortedSet.empty,
    admittedFacilitators = SortedSet.empty,
    removedFacilitators = Set(f),
    localArtifactProofSigners = SortedSet(a, b, c)
  )

  // Node B finalized a beat later having observed a STRICT SUPERSET: a fourth proof, plus
  // e's divergent-facilitatorsHash facility declaration, which it fork-evicted locally
  // (removedFacilitators gains e on this node only).
  private val nodeB = nodeA.copy(
    removedFacilitators = Set(f, e),
    localArtifactProofSigners = SortedSet(a, b, c, d)
  )

  /** Mirrors the pack at both StateAdvancers' outcome finalization (post-fix). */
  private def packEntry(view: NodeRoundView): ControllerEvidenceEntry =
    ControllerEvidenceEntry(
      roundStartFacilitators = view.roundStartFacilitators,
      completedSigners = ControllerEvidenceDerivation.canonicalCompletedSigners(
        roundStartFacilitators = view.roundStartFacilitators,
        acceptedObservedResponders = view.acceptedObservedResponders,
        certifiedEvictions = view.certifiedEvictionTargets
      ),
      timeoutVoters = view.acceptedTimeoutCertificateVoters,
      admittedPeers = view.admittedFacilitators,
      evictedPeers = view.certifiedEvictionTargets
    )

  /** The retired derivation (negative control): `roundStartFacilitators -- state.removedFacilitators`. */
  private def legacyCompletedSigners(view: NodeRoundView): SortedSet[PeerId] =
    view.roundStartFacilitators -- view.removedFacilitators

  private val key = ord(15L)

  // A shared prior window (both nodes agreed through ordinal 14).
  private val priorEvidence: SortedMap[SnapshotOrdinal, ControllerEvidenceEntry] =
    SortedMap.from((10L to 14L).map { o =>
      ord(o) -> ControllerEvidenceEntry(
        roundStartFacilitators = roundStart,
        completedSigners = roundStart,
        timeoutVoters = SortedSet.empty,
        admittedPeers = SortedSet.empty,
        evictedPeers = SortedSet.empty
      )
    })

  /** Mirrors the signed-bytes window assembly at finalization: evidence window appended with this round's entry, recentSigners window from
    * the same canonical set, recentProofSizes from the canonical committee size.
    */
  private def packSignedPayload(view: NodeRoundView) = {
    val entry = packEntry(view)
    val evidence = ControllerEvidenceDerivation.appendBounded(priorEvidence, key, entry, tighteningWindow = 10)
    val recentSigners = evidence.map { case (o, en) => o -> en.completedSigners }
    val recentProofSizes = evidence.map {
      case (o, en) =>
        o -> ControllerEvidenceDerivation.canonicalCommittee(en.roundStartFacilitators, en.evictedPeers).size
    }

    ControllerEvidenceDerivation.signedArtifactOperationalState(
      recentProofSizes = recentProofSizes,
      recentSigners = recentSigners,
      controllerEvidence = Some(evidence),
      penaltyUntil = None
    )
  }

  pureTest("superset observer packs the identical ControllerEvidenceEntry") {
    val entryA = packEntry(nodeA)
    val entryB = packEntry(nodeB)

    // The local observations really do differ (strict superset on node B)...
    expect(nodeA.localArtifactProofSigners.subsetOf(nodeB.localArtifactProofSigners)) &&
    expect(nodeA.localArtifactProofSigners != nodeB.localArtifactProofSigners) &&
    expect(nodeA.removedFacilitators.subsetOf(nodeB.removedFacilitators)) &&
    expect(nodeA.removedFacilitators != nodeB.removedFacilitators) &&
    // ...but the packed evidence is byte-identical, and reflects only proposal-carried data.
    expect.same(entryA, entryB) &&
    expect.same(SortedSet(a, b, c, d, e), entryA.completedSigners)
  }

  pureTest("superset observer packs identical signedArtifactPeerHistory bytes") {
    // Production snapshot serialization drops null values; compare with the same printer.
    val printer = Printer.noSpaces.copy(dropNullValues = true)
    val payloadA = packSignedPayload(nodeA)
    val payloadB = packSignedPayload(nodeB)

    expect.same(payloadA, payloadB) &&
    expect.same(payloadA.asJson.printWith(printer), payloadB.asJson.printWith(printer))
  }

  pureTest("the retired removedFacilitators-based derivation was a real divergence channel") {
    // Same fixtures, pre-fix derivation: node B's local fork-eviction of e reaches the
    // signer set and the two nodes' windows diverge -- the live wedge this suite pins down.
    expect(legacyCompletedSigners(nodeA) != legacyCompletedSigners(nodeB)) &&
    expect(legacyCompletedSigners(nodeA).contains(e)) &&
    expect(!legacyCompletedSigners(nodeB).contains(e))
  }

  pureTest("canonical signers never include peers outside the round-start committee") {
    // A proposal observing a candidate / withdrawn peer must not leak it into the window.
    val outsider = peer('z')
    val signers = ControllerEvidenceDerivation.canonicalCompletedSigners(
      roundStartFacilitators = roundStart,
      acceptedObservedResponders = responders + outsider,
      certifiedEvictions = certEvicted
    )

    expect(signers.subsetOf(roundStart)) &&
    expect(!signers.contains(outsider)) &&
    expect.same(SortedSet(a, b, c, d, e), signers)
  }

  pureTest("empty observedResponders (bootstrap proposals) falls back to the full canonical committee") {
    val signers = ControllerEvidenceDerivation.canonicalCompletedSigners(
      roundStartFacilitators = roundStart,
      acceptedObservedResponders = Set.empty,
      certifiedEvictions = certEvicted
    )

    expect.same(SortedSet(a, b, c, d, e), signers)
  }

  pureTest("certificate-evicted peers are excluded even when observed responding") {
    val signers = ControllerEvidenceDerivation.canonicalCompletedSigners(
      roundStartFacilitators = roundStart,
      acceptedObservedResponders = responders + f,
      certifiedEvictions = certEvicted
    )

    expect(!signers.contains(f)) &&
    expect.same(SortedSet(a, b, c, d, e), signers)
  }
}
