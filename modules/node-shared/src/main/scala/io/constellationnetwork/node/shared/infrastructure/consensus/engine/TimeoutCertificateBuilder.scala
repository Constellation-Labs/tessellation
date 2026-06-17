package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.data.NonEmptySet

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{TimeoutCertificate, TimeoutReason, TimeoutVote}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

/** Assembles timeout certificates from signed timeout votes.
  *
  * TC application reuses this builder at apply time so a locally stored or proposal-carried certificate is checked against the same quorum,
  * witness-pool, parent-hash, and highest-QC invariants as local assembly.
  */
object TimeoutCertificateBuilder {

  def build(
    fromView: Long,
    toView: Long,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    reason: TimeoutReason,
    votes: Map[PeerId, Signed[TimeoutVote]],
    quorumSize: Int,
    witnessPool: Set[PeerId]
  ): Either[CertBuildError, TimeoutCertificate] = {
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
      val wrongReason = votes.toList.collect {
        case (pid, signed) if signed.value.fromView == fromView && signed.value.toView == toView && signed.value.reason != reason =>
          pid
      }

      val matchingByView = votes.values
        .filter(signed => signed.value.fromView == fromView && signed.value.toView == toView && signed.value.reason == reason)
        .toList
      val bySigner: Map[PeerId, Signed[TimeoutVote]] =
        matchingByView.groupBy(_.proofs.head.id.toPeerId).view.mapValues(_.head).toMap
      val poolSigners: Map[PeerId, Signed[TimeoutVote]] = bySigner.filter {
        case (signer, _) => witnessPool.contains(signer)
      }

      if (wrongLastSnapshotHash.nonEmpty)
        Left(CertBuildError.LastSnapshotHashMismatch(wrongLastSnapshotHash.size))
      else if (wrongReason.nonEmpty)
        Left(CertBuildError.ReasonMismatch(wrongReason.size))
      else if (poolSigners.size < quorumSize)
        Left(CertBuildError.UnderQuorum(poolSigners.size, quorumSize))
      else {
        val matchingSigned = poolSigners.values.toList
        val qcs = matchingSigned.flatMap(_.value.highestKnownQc)
        val divergent = qcs.groupBy(_.view).exists { case (_, qcsAtView) => qcsAtView.map(_.proposalHash).toSet.size > 1 }
        if (divergent)
          Left(CertBuildError.DivergentQcs)
        else {
          val sortedSet: SortedSet[Signed[TimeoutVote]] = SortedSet.empty[Signed[TimeoutVote]] ++ matchingSigned
          NonEmptySet
            .fromSet(sortedSet)
            .toRight(CertBuildError.EmptyVotesAfterFilter)
            .map(nes => TimeoutCertificate(fromView, toView, facilitatorsHash, lastSnapshotHash, reason, nes))
        }
      }
    }
  }
}
