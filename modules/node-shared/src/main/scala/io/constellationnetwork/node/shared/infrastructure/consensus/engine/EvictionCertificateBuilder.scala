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
    *   - Reject the entire input on payload mismatches (target, reason, facilitatorsHash, lastSnapshotHash) — these indicate adversarial
    *     replay or a leader stitching votes from divergent views.
    *   - Drop votes whose signer is not in `witnessPool` SILENTLY; only pool members count toward quorum. The voter-key (storage slot)
    *     filter is implicit — a non-pool signer cannot pass the gate regardless of which storage key relayed the vote.
    *   - Count UNIQUE SIGNERS (by `proofs.head.id`) -- not storage keys -- for the quorum check. The `votes` map is keyed by the gossip
    *     sender, which is not necessarily the signer: a single signed vote can be relayed through multiple peers and end up stored under
    *     different keys. Without signer-level deduplication, a Byzantine relay can inflate the apparent quorum.
    *   - Use a SortedSet for the resulting votes so serialization order is stable.
    *
    * `witnessPool` widened from the round-start committee to `state.eligibleFacilitators - target`. Caller responsibility to compute the
    * deterministic witness set; this function gates signers against it. Quorum is still passed as a separate `quorumSize` so the caller
    * pegs it to committee size, not witness pool size.
    *
    * Non-pool voters are FILTERED instead of REJECTED. Prior fail-fast on any single non-pool voter caused the testnet wedge at ord 3121873
    * -- a stale gossip relay or mid-round eligibility shrinkage was enough to poison every cert assembly attempt (`peers=1 votes=6
    * quorum=6` -> no cert, view-change loop, no snapshots). Filter-then-quorum keeps the same security envelope (only pool members count
    * toward quorum) but no longer lets one rogue voter deadlock the cluster.
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
    witnessPool: Set[PeerId]
  ): Either[CertBuildError, EvictionCertificate] = {
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
    // Reject mixed-tip vote sets -- prevents a leader from replaying
    // older signed votes that matched the current facilitators hash but referenced a prior tip.
    val wrongLastSnapHash = votes.toList.collect {
      case (pid, signed)
          if signed.value.targetPeer == target
            && signed.value.reason == reason
            && signed.value.facilitatorsHash == facilitatorsHash
            && signed.value.lastSnapshotHash != lastSnapshotHash =>
        pid
    }

    if (wrongTarget.nonEmpty)
      Left(CertBuildError.TargetMismatch(wrongTarget.size))
    else if (wrongReason.nonEmpty)
      Left(CertBuildError.ReasonMismatch(wrongReason.size))
    else if (wrongFacHash.nonEmpty)
      Left(CertBuildError.FacilitatorsHashMismatch(wrongFacHash.size))
    else if (wrongLastSnapHash.nonEmpty)
      Left(CertBuildError.LastSnapshotHashMismatch(wrongLastSnapHash.size))
    else {
      // Deduplicate by signer BEFORE the pool filter and quorum check. A relayed duplicate of the
      // same signed vote must not count twice.
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
      val poolSigners: Map[PeerId, Signed[EvictionVote]] = bySigner.filter {
        case (signer, _) => witnessPool.contains(signer)
      }
      if (poolSigners.size < quorumSize)
        Left(CertBuildError.UnderQuorum(poolSigners.size, quorumSize))
      else {
        val sortedSet: SortedSet[Signed[EvictionVote]] = SortedSet.empty[Signed[EvictionVote]] ++ poolSigners.values
        NonEmptySet
          .fromSet(sortedSet)
          .toRight(CertBuildError.EmptyVotesAfterFilter)
          .map(nes => EvictionCertificate(target, reason, facilitatorsHash, lastSnapshotHash, nes))
      }
    }
  }
}
