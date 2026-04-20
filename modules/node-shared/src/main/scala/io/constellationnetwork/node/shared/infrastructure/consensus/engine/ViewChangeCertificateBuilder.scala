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
  * downstream VCC consumer (proposal acceptance path) — this pure builder only checks structural invariants.
  */
object ViewChangeCertificateBuilder {

  /** Build a valid VCC from a collected map of votes for a specific (fromView, toView) transition.
    *
    *   - Filter votes that match (fromView, toView, facilitatorsHash).
    *   - Require at least `quorumSize` distinct signers.
    *   - Reject if any two votes carry QCs at the same view with different proposalHashes (malformed; would make `highestQcInVcc`
    *     undefined).
    *
    * Returns `Right(vcc)` with a `NonEmptySet[Signed[ViewChangeVote]]`, or `Left(reason)` with a stable code-like string.
    */
  def build(
    fromView: Long,
    toView: Long,
    facilitatorsHash: Hash,
    votes: Map[PeerId, Signed[ViewChangeVote]],
    quorumSize: Int
  ): Either[String, ViewChangeCertificate] = {
    val matching = votes.toList.collect {
      case (_, signed) if signed.value.fromView == fromView && signed.value.toView == toView => signed
    }

    val wrongFacHash = votes.toList.collect {
      case (pid, signed)
          if signed.value.fromView == fromView && signed.value.toView == toView && signed.value.facilitatorsHash != facilitatorsHash =>
        pid
    }

    if (wrongFacHash.nonEmpty)
      Left(s"facilitators_mismatch peers=${wrongFacHash.size}")
    else if (matching.size < quorumSize)
      Left(s"under_quorum votes=${matching.size} required=$quorumSize")
    else {
      val qcs = matching.flatMap(_.value.highestKnownQc)
      val divergent = qcs.groupBy(_.view).exists { case (_, qcsAtView) => qcsAtView.map(_.proposalHash).toSet.size > 1 }
      if (divergent)
        Left("divergent_qcs")
      else {
        val sortedSet: SortedSet[Signed[ViewChangeVote]] = SortedSet.empty[Signed[ViewChangeVote]] ++ matching
        NonEmptySet
          .fromSet(sortedSet)
          .toRight("empty_votes_after_filter")
          .map(nes => ViewChangeCertificate(fromView, toView, facilitatorsHash, nes))
      }
    }
  }
}
