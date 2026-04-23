package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import cats.data.NonEmptySet

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{EvictionCertificate, EvictionReason, EvictionVote}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

/** Assembles an `EvictionCertificate` from a collected map of per-peer `EvictionVote`s targeting the same peer with the same reason.
  *
  * Mirrors `ViewChangeCertificateBuilder`. Structural-only: confirms the votes form a valid quorum-certified assertion that `target` should
  * be evicted. Signature verification of each `Signed[EvictionVote]` is deferred to the downstream consumer (proposal acceptance path) —
  * this pure builder only checks the invariants that must hold for the certificate to be well-formed.
  *
  * The caller (`EvictionVoter`) pulls votes from `ConsensusStorage.evictionVotes(target)` after new votes arrive; on success, the assembled
  * certificate is stored via `ConsensusStorage.storeAssembledEvictionCertificate` so the next proposer can embed it in its Proposal.
  */
object EvictionCertificateBuilder {

  /** Build a valid EvictionCertificate for a specific (target, reason) pair.
    *
    *   - Filter votes to those matching (target, reason, facilitatorsHash).
    *   - Reject any vote whose voter is not in the current committee.
    *   - Count UNIQUE SIGNERS (by `proofs.head.id`) — not storage keys — for the quorum check. The `votes` map is keyed by the gossip
    *     sender, which is not necessarily the signer: a single signed vote can be relayed through multiple peers and end up stored under
    *     different keys. Without signer-level deduplication, a Byzantine relay can inflate the apparent quorum, get a cert assembled, and
    *     then see it rejected at proposal-acceptance time (where `validateProposalEcs` re-checks against the deduplicated `cert.votes`
    *     set). Deduplicating here ensures the cert is only built from distinct signers.
    *   - Use a SortedSet for the resulting votes so serialization order is stable.
    *
    * Returns `Right(cert)` on success, or `Left(reason)` with a stable code-like string.
    */
  def build(
    target: PeerId,
    reason: EvictionReason,
    facilitatorsHash: Hash,
    lastSnapshotHash: Hash,
    votes: Map[PeerId, Signed[EvictionVote]],
    quorumSize: Int,
    committee: Set[PeerId]
  ): Either[String, EvictionCertificate] = {
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
    // Codex review 2026-04-23: reject mixed-tip vote sets — prevents a leader from replaying
    // older signed votes that matched the current facilitators hash but referenced a prior tip.
    val wrongLastSnapHash = votes.toList.collect {
      case (pid, signed)
          if signed.value.targetPeer == target
            && signed.value.reason == reason
            && signed.value.facilitatorsHash == facilitatorsHash
            && signed.value.lastSnapshotHash != lastSnapshotHash =>
        pid
    }
    val nonCommitteeVoter = votes.toList.collect {
      case (voter, _) if !committee.contains(voter) => voter
    }

    if (wrongTarget.nonEmpty)
      Left(s"target_mismatch peers=${wrongTarget.size}")
    else if (wrongReason.nonEmpty)
      Left(s"reason_mismatch peers=${wrongReason.size}")
    else if (wrongFacHash.nonEmpty)
      Left(s"facilitators_mismatch peers=${wrongFacHash.size}")
    else if (wrongLastSnapHash.nonEmpty)
      Left(s"last_snapshot_hash_mismatch peers=${wrongLastSnapHash.size}")
    else if (nonCommitteeVoter.nonEmpty)
      Left(s"voter_not_in_committee peers=${nonCommitteeVoter.size}")
    else {
      // Deduplicate by signer BEFORE checking quorum. A relayed duplicate of the same signed
      // vote must not count twice, otherwise an adversary can fabricate under-quorum certs
      // that followers later reject in validateProposalEcs.
      val bySigner: Map[PeerId, Signed[EvictionVote]] = votes.values
        .filter(signed =>
          signed.value.targetPeer == target
            && signed.value.reason == reason
            && signed.value.facilitatorsHash == facilitatorsHash
            && signed.value.lastSnapshotHash == lastSnapshotHash
        )
        .toList
        .groupBy(_.proofs.head.id.toPeerId)
        // Within a group, all entries are the same logical vote (same signer, same payload).
        // `head` picks one deterministically — all candidates share the same bytes.
        .view
        .mapValues(_.head)
        .toMap
      val nonCommitteeSigner = bySigner.keys.filterNot(committee.contains).toList
      if (nonCommitteeSigner.nonEmpty)
        Left(s"signer_not_in_committee peers=${nonCommitteeSigner.size}")
      else if (bySigner.size < quorumSize)
        Left(s"under_quorum votes=${bySigner.size} required=$quorumSize")
      else {
        val sortedSet: SortedSet[Signed[EvictionVote]] = SortedSet.empty[Signed[EvictionVote]] ++ bySigner.values
        NonEmptySet
          .fromSet(sortedSet)
          .toRight("empty_votes_after_filter")
          .map(nes => EvictionCertificate(target, reason, facilitatorsHash, lastSnapshotHash, nes))
      }
    }
  }
}
