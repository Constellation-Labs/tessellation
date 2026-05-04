package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.data.NonEmptySet

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{AdmissionCertificate, AdmissionReason, AdmissionVote}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

/** Assembles an `AdmissionCertificate` from a collected map of per-peer `AdmissionVote`s targeting the same peer with the same reason (B2).
  *
  * Mirrors `EvictionCertificateBuilder`. Structural-only: confirms the votes form a valid quorum-certified assertion that `target` is ready
  * to be re-admitted. Signature verification of each `Signed[AdmissionVote]` is deferred to the downstream consumer (proposal acceptance
  * path) — this pure builder only checks the invariants that must hold for the certificate to be well-formed.
  *
  * The caller (`AdmissionVoter`) pulls votes from `ConsensusStorage.admissionVotes(target)` after new votes arrive; on success, the
  * assembled certificate is stored via `ConsensusStorage.storeAssembledAdmissionCertificate` so the next proposer can embed it in its
  * Proposal.
  */
object AdmissionCertificateBuilder {

  /** Build a valid AdmissionCertificate for a specific (target, reason) pair.
    *
    * Invariant checks are identical to the eviction path — filter by (target, reason, facilitatorsHash), reject voters outside the
    * `witnessPool`, count UNIQUE SIGNERS for the quorum check. See `EvictionCertificateBuilder` for the full rationale on relay-duplicate
    * deduplication and on the v9 widening of the witness pool from committee to `state.eligibleFacilitators - target`.
    */
  def build(
    target: PeerId,
    reason: AdmissionReason,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    votes: Map[PeerId, Signed[AdmissionVote]],
    quorumSize: Int,
    witnessPool: Set[PeerId]
  ): Either[CertBuildError, AdmissionCertificate] = {
    val wrongTarget = votes.toList.collect {
      case (pid, signed) if signed.value.targetPeer != target => pid
    }
    val wrongReason = votes.toList.collect {
      case (pid, signed) if signed.value.targetPeer == target && signed.value.reason != reason => pid
    }
    val wrongFacHash = votes.toList.collect {
      case (pid, signed)
          if signed.value.targetPeer == target
            && signed.value.reason == reason
            && signed.value.facilitatorsHash != facilitatorsHash =>
        pid
    }
    // Codex review 2026-04-23: reject mixed-tip vote sets. Without this, signed votes that
    // targeted an earlier tip with the same facilitators hash could be stitched into a fresh-
    // looking cert. Every vote in the assembled cert must match the current expected tip.
    val wrongLastSnapHash = votes.toList.collect {
      case (pid, signed)
          if signed.value.targetPeer == target
            && signed.value.reason == reason
            && signed.value.facilitatorsHash == facilitatorsHash
            && signed.value.lastSnapshotHash != lastSnapshotHash =>
        pid
    }
    val nonWitnessPoolVoter = votes.toList.collect {
      case (voter, _) if !witnessPool.contains(voter) => voter
    }

    if (wrongTarget.nonEmpty)
      Left(CertBuildError.TargetMismatch(wrongTarget.size))
    else if (wrongReason.nonEmpty)
      Left(CertBuildError.ReasonMismatch(wrongReason.size))
    else if (wrongFacHash.nonEmpty)
      Left(CertBuildError.FacilitatorsHashMismatch(wrongFacHash.size))
    else if (wrongLastSnapHash.nonEmpty)
      Left(CertBuildError.LastSnapshotHashMismatch(wrongLastSnapHash.size))
    else if (nonWitnessPoolVoter.nonEmpty)
      Left(CertBuildError.VoterNotInCommittee(nonWitnessPoolVoter.size))
    else {
      // Deduplicate by signer BEFORE checking quorum — see EvictionCertificateBuilder for rationale.
      val bySigner: Map[PeerId, Signed[AdmissionVote]] = votes.values
        .filter(signed =>
          signed.value.targetPeer == target
            && signed.value.reason == reason
            && signed.value.facilitatorsHash == facilitatorsHash
            && signed.value.lastSnapshotHash == lastSnapshotHash
        )
        .toList
        .groupBy(_.proofs.head.id.toPeerId)
        .view
        .mapValues(_.head)
        .toMap
      val nonWitnessPoolSigner = bySigner.keys.filterNot(witnessPool.contains).toList
      if (nonWitnessPoolSigner.nonEmpty)
        Left(CertBuildError.SignerNotInCommittee(nonWitnessPoolSigner.size))
      else if (bySigner.size < quorumSize)
        Left(CertBuildError.UnderQuorum(bySigner.size, quorumSize))
      else {
        val sortedSet: SortedSet[Signed[AdmissionVote]] = SortedSet.empty[Signed[AdmissionVote]] ++ bySigner.values
        NonEmptySet
          .fromSet(sortedSet)
          .toRight(CertBuildError.EmptyVotesAfterFilter)
          .map(nes => AdmissionCertificate(target, reason, facilitatorsHash, lastSnapshotHash, nes))
      }
    }
  }
}
