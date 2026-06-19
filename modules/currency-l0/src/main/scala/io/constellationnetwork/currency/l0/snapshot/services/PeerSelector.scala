package io.constellationnetwork.currency.l0.snapshot.services

import java.security.MessageDigest

import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

object PeerSelector {

  /** Deterministically pick the single metagraph node responsible for posting a binary.
    *
    * `alivePeers` is the set of currently-responsive peers (plus self). When provided, dead peers are removed from the candidate set BEFORE
    * selection, so a binary is never assigned to a node that cannot send it; if no candidate is alive, this node takes over
    * (self-fallback). When `None` (liveness unknown), no filtering is applied and selection degrades to the pure deterministic behaviour.
    * Selection is stable across nodes that share the same alive view, so exactly one node selects itself in the common case.
    */
  def pickDeterministicPeer(
    binarySigners: List[PeerId],
    allowedPeers: List[PeerId],
    selfId: PeerId,
    lastSnapshotHash: Hash,
    alivePeers: Option[Set[PeerId]]
  ): PeerId = {
    def keepLive(peers: List[PeerId]): List[PeerId] =
      alivePeers.fold(peers)(live => peers.filter(p => live.contains(p) || p === selfId))

    if (binarySigners.isEmpty) {
      selfId
    } else {
      val liveSigners = keepLive(binarySigners)
      if (liveSigners.isEmpty) {
        selfId
      } else if (liveSigners.size === 1) {
        liveSigners.head
      } else if (allowedPeers.isEmpty) {
        selectFromSigners(liveSigners, lastSnapshotHash)
      } else {
        selectFromEligiblePeers(liveSigners, keepLive(allowedPeers), lastSnapshotHash)
      }
    }
  }

  private def selectFromSigners(signers: List[PeerId], seed: Hash): PeerId = {
    val sortedSigners = signers.sortBy(_.toString)
    val offset = computeOffset(sortedSigners, seed)
    sortedSigners(offset)
  }

  private def selectFromEligiblePeers(
    binarySigners: List[PeerId],
    allowedPeers: List[PeerId],
    lastSnapshotHash: Hash
  ): PeerId = {
    val eligiblePeers = binarySigners.filter(allowedPeers.contains)
    if (eligiblePeers.isEmpty) {
      // No signer is in the allowance list: fall back to a single deterministic signer rather than `selfId`,
      // which would make every node post the same binary (thundering herd).
      selectFromSigners(binarySigners, lastSnapshotHash)
    } else {
      val sortedEligible = eligiblePeers.sortBy(_.toString)
      val offset = computeOffset(sortedEligible, lastSnapshotHash)
      sortedEligible(offset)
    }
  }

  private def computeOffset(peers: List[PeerId], seed: Hash): Int = {
    val hashInput = seed.value + peers.map(_.toString).mkString("|")
    val digest = MessageDigest.getInstance("SHA-256").digest(hashInput.getBytes("UTF-8"))
    val hashValue = BigInt(digest.take(4)).abs
    (hashValue % peers.size).toInt
  }
}
