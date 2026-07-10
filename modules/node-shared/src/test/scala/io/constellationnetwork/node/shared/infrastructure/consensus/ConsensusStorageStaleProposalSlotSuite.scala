package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet

import scala.collection.immutable.SortedMap

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.FunSuite

/** Regression coverage for the alpha.93 stale-proposal-slot prune predicate.
  *
  * Closes the alpha.92 9h wedge at ord 3127095 (`project_alpha92_wedge_may21.md`). A leader's `Proposal(view=16, vcc=None)` arrived at
  * peers BEFORE they entered the round under that leader; once the round advanced past view 16, the cached slot was guaranteed to fail
  * validation forever (`view16_proposal_missing_vcc` -- 10,333 rejections in 9h on .193) because `ProposalVccValidator` only bypasses the
  * missing-VCC check when `proposalView == initialViewNumber` (certified seed-view) or in solo-core mode. `ConsensusStorage.addProposal`
  * first-write-wins for higher-view-without-cert also blocked replacement.
  *
  * The fix in `ConsensusStorage.pruneStaleProposalSlots` drops slots where `proposal.view < minViewToKeep` and no view certificate is
  * present. This suite drives the same predicate against an in-memory map shaped identically to `peerDeclarationsMap` so we cover every
  * cell of the truth table without instantiating the full storage -- the storage itself requires many typeclass witnesses to construct
  * (mirrors the `ConsensusStoragePruneSuite` pattern for `pruneStaleResources`).
  */
object ConsensusStorageStaleProposalSlotSuite extends FunSuite {

  private val anyHash: Hash = Hash.fromBytes("any_hash".getBytes("UTF-8"))
  private val facHash: Hash = Hash.fromBytes("fac_hash".getBytes("UTF-8"))
  private val lastSnap: Hash = Hash.fromBytes("last_snap".getBytes("UTF-8"))

  private val leaderA: PeerId = PeerId(Hex("aa" * 32))
  private val leaderB: PeerId = PeerId(Hex("bb" * 32))

  // A minimal non-empty VCC for the "keep" branch -- only `vcc.isEmpty` is consulted by the prune
  // predicate, so the cert internals don't need to be cryptographically valid. Same convention as
  // ProposalVccValidatorSuite's vote fixtures (deterministic tag-based signers, no real signing).
  private val sentinelVcc: ViewChangeCertificate = {
    val signedVote = Signed(
      ViewChangeVote(fromView = 0L, toView = 1L, facilitatorsHash = facHash, lastSnapshotHash = lastSnap, highestKnownQc = None),
      NonEmptySet.one(SignatureProof(Id(Hex("aa" * 32)), Signature(Hex("00"))))
    )
    ViewChangeCertificate(
      fromView = 0L,
      toView = 1L,
      facilitatorsHash = facHash,
      votes = NonEmptySet.one(signedVote)
    )
  }

  private val sentinelTc: TimeoutCertificate = {
    val signedVote = Signed(
      TimeoutVote(
        fromView = 0L,
        toView = 1L,
        facilitatorsHash = facHash,
        lastSnapshotHash = lastSnap,
        highestKnownQc = None,
        reason = TimeoutReason.NoProgress
      ),
      NonEmptySet.one(SignatureProof(Id(Hex("bb" * 32)), Signature(Hex("00"))))
    )
    TimeoutCertificate(
      fromView = 0L,
      toView = 1L,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      reason = TimeoutReason.NoProgress,
      votes = NonEmptySet.one(signedVote)
    )
  }

  private def proposal(view: Long, hasVcc: Boolean, hasTc: Boolean = false): Proposal =
    Proposal(
      hash = anyHash,
      facilitatorsHash = facHash,
      lastSnapshotHash = lastSnap,
      view = view,
      vcc = if (hasVcc) Some(sentinelVcc) else None,
      timeoutCertificate = if (hasTc) Some(sentinelTc) else None,
      evictionCertificates = List.empty,
      admissionCertificates = List.empty,
      observedResponders = List.empty,
      observedSelfHealth = SortedMap.empty
    )

  /** Mirrors the lambda body inside `ConsensusStorage.pruneStaleProposalSlots`. */
  private def applyPrune(
    declMap: Map[PeerId, PeerDeclarations],
    minViewToKeep: Long
  ): Map[PeerId, PeerDeclarations] =
    declMap.map {
      case (peerId, decl) =>
        val updated = decl.proposal match {
          case Some(p) if p.view < minViewToKeep && p.vcc.isEmpty && p.timeoutCertificate.isEmpty =>
            decl.copy(proposal = None)
          case _ => decl
        }
        peerId -> updated
    }

  private def declWithProposal(p: Proposal): PeerDeclarations =
    PeerDeclarations.empty.copy(proposal = Some(p))

  test("drops a stored proposal with view < minViewToKeep AND no view cert -- the alpha.92 wedge pattern") {
    val before = Map(leaderA -> declWithProposal(proposal(view = 16L, hasVcc = false)))
    val after = applyPrune(before, minViewToKeep = 18L)
    expect(after(leaderA).proposal.isEmpty)
  }

  test("keeps a stored proposal at exactly the seed view (proposalView == minViewToKeep)") {
    val before = Map(leaderA -> declWithProposal(proposal(view = 18L, hasVcc = false)))
    val after = applyPrune(before, minViewToKeep = 18L)
    expect(after(leaderA).proposal.isDefined)
  }

  test("keeps a stored proposal that DOES carry a VCC, even if view < minViewToKeep") {
    val before = Map(leaderA -> declWithProposal(proposal(view = 16L, hasVcc = true)))
    val after = applyPrune(before, minViewToKeep = 18L)
    expect(after(leaderA).proposal.isDefined)
  }

  test("keeps a stored proposal that DOES carry a TC, even if view < minViewToKeep") {
    val before = Map(leaderA -> declWithProposal(proposal(view = 16L, hasVcc = false, hasTc = true)))
    val after = applyPrune(before, minViewToKeep = 18L)
    expect(after(leaderA).proposal.isDefined)
  }

  test("keeps a stored proposal at view > minViewToKeep (forward proposal)") {
    val before = Map(leaderA -> declWithProposal(proposal(view = 22L, hasVcc = false)))
    val after = applyPrune(before, minViewToKeep = 18L)
    expect(after(leaderA).proposal.isDefined)
  }

  test("does not touch peers that have no stored proposal") {
    val before = Map(leaderA -> PeerDeclarations.empty)
    val after = applyPrune(before, minViewToKeep = 18L)
    expect(after(leaderA).proposal.isEmpty && after(leaderA).facility.isEmpty)
  }

  test("prunes each peer independently -- mixed table") {
    val before = Map(
      leaderA -> declWithProposal(proposal(view = 16L, hasVcc = false)), // drop
      leaderB -> declWithProposal(proposal(view = 22L, hasVcc = false)) // keep
    )
    val after = applyPrune(before, minViewToKeep = 18L)
    expect.all(
      after(leaderA).proposal.isEmpty,
      after(leaderB).proposal.isDefined
    )
  }

  test("idempotent re-invocation -- the second prune is a no-op") {
    val before = Map(leaderA -> declWithProposal(proposal(view = 16L, hasVcc = false)))
    val once = applyPrune(before, minViewToKeep = 18L)
    val twice = applyPrune(once, minViewToKeep = 18L)
    expect(once == twice)
  }

  test("no-op when minViewToKeep is 0 (seed view 0 path -- no stale slots possible)") {
    val before = Map(leaderA -> declWithProposal(proposal(view = 0L, hasVcc = false)))
    val after = applyPrune(before, minViewToKeep = 0L)
    expect(after(leaderA).proposal.isDefined)
  }
}
