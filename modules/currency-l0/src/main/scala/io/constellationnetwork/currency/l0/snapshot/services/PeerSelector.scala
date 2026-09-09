package io.constellationnetwork.currency.l0.snapshot.services

import java.security.MessageDigest

import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

object PeerSelector {

  /** Deterministically pick the single metagraph node responsible for posting a binary.
    *
    * The PRIMARY owner and the rotation order are computed over the FULL candidate set (liveness-independent), seeded by `lastSnapshotHash`
    * — so every node, regardless of its local view of who is alive, agrees on the same primary and the same ordering. `alivePeers` is then
    * used ONLY to skip past dead peers along that shared rotation and let the next live one (or self) take over.
    *
    * Consequences:
    *   - When the primary is alive, it always selects itself (it sees itself alive), so there is exactly one sender — no dependency on
    *     other nodes agreeing about anyone's liveness.
    *   - When the primary is dead, nodes converge on the same next-in-rotation live peer; transient alive-view disagreement can at worst
    *     cause a (harmless, hash-deduplicated) duplicate send, never silence.
    *   - When `alivePeers` is None (liveness unknown), selection degrades to the pure deterministic primary.
    */
  def pickDeterministicPeer(
    binarySigners: List[PeerId],
    allowedPeers: List[PeerId],
    selfId: PeerId,
    lastSnapshotHash: Hash,
    alivePeers: Option[Set[PeerId]]
  ): PeerId =
    candidateSet(binarySigners, allowedPeers) match {
      case Nil => selfId
      case candidates =>
        val sorted = candidates.distinct.sortBy(_.toString)
        val primaryIdx = computeOffset(sorted, lastSnapshotHash)
        alivePeers match {
          case None => sorted(primaryIdx)
          case Some(live) =>
            val n = sorted.size
            (0 until n).iterator
              .map(i => sorted((primaryIdx + i) % n))
              .find(p => live.contains(p) || p === selfId)
              .getOrElse(selfId)
        }
    }

  /** The liveness-independent candidate set: prefer signers intersected with the allowance list, but never let an empty intersection
    * collapse to "self on every node" (thundering herd) — fall back to the full signer set.
    */
  private def candidateSet(binarySigners: List[PeerId], allowedPeers: List[PeerId]): List[PeerId] =
    if (binarySigners.isEmpty) Nil
    else if (allowedPeers.isEmpty) binarySigners
    else {
      val eligible = binarySigners.filter(allowedPeers.contains)
      if (eligible.isEmpty) binarySigners else eligible
    }

  private def computeOffset(peers: List[PeerId], seed: Hash): Int = {
    val hashInput = seed.value + peers.map(_.toString).mkString("|")
    val digest = MessageDigest.getInstance("SHA-256").digest(hashInput.getBytes("UTF-8"))
    val hashValue = BigInt(digest.take(4)).abs
    (hashValue % peers.size).toInt
  }
}
