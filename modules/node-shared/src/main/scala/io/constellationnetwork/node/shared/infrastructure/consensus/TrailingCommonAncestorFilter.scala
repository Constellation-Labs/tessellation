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
  * ==Solution: Early/Recent Split==
  *
  * Split the lookback window into two regions:
  *   - '''Early''' (first `lookbackWindow - minParticipation` snapshots): establishes who was previously active
  *   - '''Recent''' (last `minParticipation` snapshots): establishes who is currently active
  *
  * A peer is '''degraded''' if it signed snapshots in the early region but did NOT sign any in the recent region. This means it was active
  * but has gone silent — exactly the case that causes facilitatorsHash mismatches.
  *
  * ==Why This Works==
  *
  * Since `Signed[Snapshot].proofs` records who actually signed each finalized snapshot, and all nodes agree on finalized snapshots, the
  * early/recent split is fully deterministic.
  *
  * ==New Peer Onboarding==
  *
  * Peers that '''only''' appear in the recent region (they just joined) are '''not''' flagged as degraded — they have no early history to
  * compare against. Peers with '''zero''' appearances anywhere in the window are also safe. This avoids the chicken-and-egg problem where a
  * new peer could never become eligible because it has no history.
  *
  * ==Post-Rollback Behavior==
  *
  * After a network rollback where 1 node runs solo, then others join:
  *   - Early region: only the solo node signed
  *   - Recent region: solo node + newly joined peers signed
  *   - earlySigners = {solo}, recentSigners = {solo, new1, new2, ...}
  *   - degraded = earlySigners -- recentSigners = {} (empty)
  *   - Result: all peers pass TCA, new peers can participate immediately
  *
  * ==Graceful Degradation==
  *
  * Returns `None` (signaling no exclusions / fallback to current behavior) when:
  *   - Current ordinal is too low for sufficient history
  *   - The early region is empty (can't determine who was previously active)
  *   - Historical snapshots are unavailable in storage
  */
trait TrailingCommonAncestorFilter[F[_]] {

  /** Compute the set of peers that should be excluded from facilitator selection.
    *
    * Excluded peers are those that signed snapshots in the early region of the lookback window but did NOT sign any in the recent region —
    * i.e., they were active but have gone silent. Peers that only appear in the recent region (new joiners) and peers with zero appearances
    * are not excluded.
    *
    * @param currentOrdinal
    *   the ordinal being decided (lookback examines ordinals before this)
    * @return
    *   Some(degradedPeers) if TCA has enough history to identify degraded peers, None to signal fallback (no exclusions)
    */
  def degradedPeers(currentOrdinal: SnapshotOrdinal): F[Option[Set[PeerId]]]
}

object TrailingCommonAncestorFilter {

  /** Create a TCA filter that examines historical snapshot proofs using an early/recent split.
    *
    * The lookback window is split into:
    *   - '''Early''' region: first `lookbackWindow - minParticipation` snapshots
    *   - '''Recent''' region: last `minParticipation` snapshots
    *
    * A peer is degraded if it appears in the early region but not in the recent region.
    *
    * @param getSnapshot
    *   function to retrieve a signed snapshot by ordinal
    * @param lookbackWindow
    *   K: number of past snapshots to examine (must be > minParticipation for meaningful early region)
    * @param minParticipation
    *   number of most-recent snapshots that define the "recent" region
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

      if (targetOrdinals.size < lookbackWindow)
        none[Set[PeerId]].pure[F]
      else
        targetOrdinals
          .traverse(ord => getSnapshot(ord).map(snap => (ord, snap)))
          .map { results =>
            val available = results.collect { case (ord, Some(snap)) => (ord, snap) }

            if (available.size < minParticipation)
              none[Set[PeerId]]
            else {
              // Sort by ordinal ascending and split into early/recent
              val sorted = available.sortBy(_._1.value.value)
              val recentCount = math.min(minParticipation, sorted.size)
              val earlySnapshots = sorted.dropRight(recentCount)
              val recentSnapshots = sorted.takeRight(recentCount)

              // Early region empty means we can't distinguish "was active before" from "just joined"
              // This happens when lookbackWindow == minParticipation or not enough early snapshots available
              if (earlySnapshots.isEmpty)
                none[Set[PeerId]]
              else {
                val earlySigners = earlySnapshots.flatMap { case (_, snap) => extractSigners(snap) }.toSet
                val recentSigners = recentSnapshots.flatMap { case (_, snap) => extractSigners(snap) }.toSet

                // Degraded = signed early snapshots but NOT any recent snapshots
                val degraded = earlySigners -- recentSigners

                degraded.some
              }
            }
          }
    }

    private def extractSigners(signed: Signed[S]): Set[PeerId] =
      signed.proofs.map(_.id.toPeerId).toSortedSet.toSet
  }
}
