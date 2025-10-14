package io.constellationnetwork.currency.l0.snapshot.services

import java.security.MessageDigest

import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

object PeerSelector {
  def pickDeterministicPeer(
    binarySigners: List[PeerId],
    allowedPeers: List[PeerId],
    selfId: PeerId,
    lastSnapshotHash: Hash
  ): PeerId =
    if (binarySigners.isEmpty) {
      selfId
    } else if (binarySigners.size === 1) {
      binarySigners.head
    } else if (allowedPeers.isEmpty) {
      selectFromSigners(binarySigners, lastSnapshotHash)
    } else {
      selectFromEligiblePeers(binarySigners, allowedPeers, selfId, lastSnapshotHash)
    }

  private def selectFromSigners(signers: List[PeerId], seed: Hash): PeerId = {
    val sortedSigners = signers.sortBy(_.toString)
    val offset = computeOffset(sortedSigners, seed)
    sortedSigners(offset)
  }

  private def selectFromEligiblePeers(
    binarySigners: List[PeerId],
    allowedPeers: List[PeerId],
    selfId: PeerId,
    lastSnapshotHash: Hash
  ): PeerId = {
    val eligiblePeers = binarySigners.filter(allowedPeers.contains)
    if (eligiblePeers.isEmpty) {
      selfId
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
