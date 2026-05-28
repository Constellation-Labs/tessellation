package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.data.NonEmptySet

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{ViewChangeCertificate, ViewChangeVote}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

/** Assembles a `ViewChangeCertificate` from a collected map of per-peer `ViewChangeVote`s.
  *
  * This is the validation step between "we've seen N view-change votes in storage" and "we have a quorum-sized, well-formed certificate
  * suitable for justifying a view-transition". Signature verification of each included `Signed[ViewChangeVote]` is deferred to the
  * downstream VCC consumer (proposal acceptance path) -- this pure builder only checks structural invariants.
  */
object ViewChangeCertificateBuilder {

  /** Build a valid VCC from a collected map of votes for a specific (fromView, toView) transition.
    *
    *   - Filter votes that match (fromView, toView, facilitatorsHash, lastSnapshotHash).
    *   - Filter signers that are not in `witnessPool` SILENTLY (mirror of `EvictionCertificateBuilder`'s v15 behavior -- a stale relay or
    *     mid-round eligibility shrinkage must not poison the assembly).
    *   - Deduplicate by signer (`proofs.head.id`) -- a relayed duplicate of the same signed vote must not count twice.
    *   - Require at least `quorumSize` distinct in-pool signers.
    *   - Reject if any two votes carry QCs at the same view with different proposalHashes (malformed; would make `highestQcInVcc`
    *     undefined).
    *
    * `witnessPool` added so VCC matches the determinism contract of `EvictionCertificateBuilder` and the wider pool can include historical
    * participants from `peerQuality`. The pool is computed by the caller from consensus-agreed inputs (see
    * `StateTransitions.widerWitnessPoolAll`); this builder treats it as opaque. Without this filter, an attacker (or a stale relay) could
    * deliver a `ViewChangeVote` from a peer that's not in the round's eligible/historical set, and earlier the cert builder would happily
    * count it toward quorum -- diverging from the proposal-validation path that re-derives the same pool and rejects out-of-pool voters.
    *
    * Returns `Right(vcc)` with a `NonEmptySet[Signed[ViewChangeVote]]`, or `Left(reason)` with a stable code-like string.
    */
  def build(
    fromView: Long,
    toView: Long,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    votes: Map[PeerId, Signed[ViewChangeVote]],
    quorumSize: Int,
    witnessPool: Set[PeerId]
  ): Either[CertBuildError, ViewChangeCertificate] = {
    val wrongFacHash = votes.toList.collect {
      case (pid, signed)
          if signed.value.fromView == fromView && signed.value.toView == toView && signed.value.facilitatorsHash != facilitatorsHash =>
        pid
    }

    if (wrongFacHash.nonEmpty)
      Left(CertBuildError.FacilitatorsHashMismatch(wrongFacHash.size))
    else {
      val wrongLastSnapshotHash = votes.toList.collect {
        case (pid, signed)
            if signed.value.fromView == fromView && signed.value.toView == toView && signed.value.lastSnapshotHash != lastSnapshotHash =>
          pid
      }

      // Filter to matching (fromView, toView) only; the facilitatorsHash filter above is fatal,
      // so anything reaching here already shares the correct round payload.
      val matchingByView = votes.values
        .filter(signed => signed.value.fromView == fromView && signed.value.toView == toView)
        .toList
      // Deduplicate by signer. Multiple votes from the same signer under different storage keys
      // (relay duplicates) collapse to one -- `head` is deterministic because all entries in the
      // group are byte-identical signed payloads.
      val bySigner: Map[PeerId, Signed[ViewChangeVote]] =
        matchingByView.groupBy(_.proofs.head.id.toPeerId).view.mapValues(_.head).toMap
      val poolSigners: Map[PeerId, Signed[ViewChangeVote]] = bySigner.filter {
        case (signer, _) => witnessPool.contains(signer)
      }
      if (wrongLastSnapshotHash.nonEmpty)
        Left(CertBuildError.LastSnapshotHashMismatch(wrongLastSnapshotHash.size))
      else if (poolSigners.size < quorumSize)
        Left(CertBuildError.UnderQuorum(poolSigners.size, quorumSize))
      else {
        val matchingSigned = poolSigners.values.toList
        val qcs = matchingSigned.flatMap(_.value.highestKnownQc)
        val divergent = qcs.groupBy(_.view).exists { case (_, qcsAtView) => qcsAtView.map(_.proposalHash).toSet.size > 1 }
        if (divergent)
          Left(CertBuildError.DivergentQcs)
        else {
          val sortedSet: SortedSet[Signed[ViewChangeVote]] = SortedSet.empty[Signed[ViewChangeVote]] ++ matchingSigned
          NonEmptySet
            .fromSet(sortedSet)
            .toRight(CertBuildError.EmptyVotesAfterFilter)
            .map(nes => ViewChangeCertificate(fromView, toView, facilitatorsHash, nes))
        }
      }
    }
  }
}
