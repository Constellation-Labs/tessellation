package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.schema.ID.Id._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.Signed

/** Ouroboros-inspired Trailing Common Ancestor filter for facilitator selection.
  *
  * ==Problem==
  *
  * When facilitators go offline between rounds, surviving nodes detect them as "abandoned missing" but cannot exclude them from the
  * eligible set — each node's local observations differ, causing `facilitatorsHash` mismatches and forks.
  *
  * ==Solution==
  *
  * Derive exclusions from historical snapshot signers. Since `Signed[Snapshot].proofs` records who actually signed each finalized snapshot,
  * and all nodes agree on finalized snapshots, this is fully deterministic. A peer that appeared in the lookback window but signed fewer
  * than `minParticipation` snapshots is considered degraded and excluded.
  *
  * ==New Peer Onboarding==
  *
  * Peers with '''zero''' appearances in the lookback window are presumed new (they joined after the window). Since joining is coordinated
  * through the cluster protocol, all nodes agree on new peers. These peers are '''not excluded''' — only peers that were recently active
  * but degraded (appeared in some but not enough snapshots) are filtered out. This avoids the chicken-and-egg problem where a new peer
  * could never become eligible because it has no history.
  *
  * ==Graceful Degradation==
  *
  * Returns `None` (signaling no exclusions / fallback to current behavior) when:
  *   - Current ordinal is too low for sufficient history
  *   - Historical snapshots are unavailable in storage
  */
trait TrailingCommonAncestorFilter[F[_]] {

  /** Compute the set of peers that should be excluded from facilitator selection.
    *
    * Excluded peers are those that appeared in the lookback window (signed at least one snapshot) but did not meet the minimum
    * participation threshold — i.e., they were recently active but degraded. Peers with zero appearances (new joiners) are not excluded.
    *
    * @param currentOrdinal
    *   the ordinal being decided (lookback examines ordinals before this)
    * @return
    *   Some(degradedPeers) if TCA has enough history to identify degraded peers, None to signal fallback (no exclusions)
    */
  def degradedPeers(currentOrdinal: SnapshotOrdinal): F[Option[Set[PeerId]]]
}

object TrailingCommonAncestorFilter {

  /** Create a TCA filter that examines historical snapshot proofs.
    *
    * The filter identifies '''degraded''' peers: those that signed at least one snapshot in the lookback window but fewer than
    * `minParticipation`. Peers with zero signatures in the window are considered new and are not flagged.
    *
    * @param getSnapshot
    *   function to retrieve a signed snapshot by ordinal
    * @param lookbackWindow
    *   K: number of past snapshots to examine
    * @param minParticipation
    *   minimum number of snapshots a peer must have signed within the window to not be considered degraded
    */
  def make[F[_]: Async, S](
    getSnapshot: SnapshotOrdinal => F[Option[Signed[S]]],
    lookbackWindow: Int,
    minParticipation: Int
  ): TrailingCommonAncestorFilter[F] = new TrailingCommonAncestorFilter[F] {

    def degradedPeers(currentOrdinal: SnapshotOrdinal): F[Option[Set[PeerId]]] = {
      val targetOrdinals: List[SnapshotOrdinal] =
        (1 to lookbackWindow).toList.flatMap { offset =>
          SnapshotOrdinal(currentOrdinal.value.value - offset)
        }

      if (targetOrdinals.size < minParticipation)
        none[Set[PeerId]].pure[F]
      else
        targetOrdinals
          .traverse(getSnapshot)
          .map { results =>
            val availableSnapshots = results.flatten
            if (availableSnapshots.size < minParticipation)
              none[Set[PeerId]]
            else {
              val participationCounts: Map[PeerId, Int] =
                availableSnapshots
                  .flatMap(extractSigners)
                  .groupBy(identity)
                  .map { case (pid, occurrences) => pid -> occurrences.size }

              // Degraded = appeared in window (count > 0) but below threshold (count < minParticipation).
              // Peers with 0 appearances are NOT in this map — they're new joiners and remain eligible.
              val degraded = participationCounts.collect {
                case (pid, count) if count < minParticipation => pid
              }.toSet

              degraded.some
            }
          }
    }

    private def extractSigners(signed: Signed[S]): Set[PeerId] =
      signed.proofs.map(_.id.toPeerId).toSortedSet.toSet
  }
}
