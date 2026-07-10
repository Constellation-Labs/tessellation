package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId

/** Ouroboros-inspired Trailing Common Ancestor filter for facilitator selection.
  *
  * ==Problem==
  *
  * When facilitators go offline between rounds, surviving nodes detect them as "abandoned missing" but cannot exclude them from the
  * eligible set — each node's local observations differ, causing `facilitatorsHash` mismatches and forks.
  *
  * ==Solution: Proofs-Based Detection==
  *
  * Compare the agreed-upon facilitator set from `lastOutcome` with the actual signers of the last finalized snapshot
  * (`lastOutcome.finished.signedMajorityArtifact.proofs`). Peers that were facilitators but did NOT sign the last snapshot are
  * '''degraded''' — they were supposed to be active but failed to participate.
  *
  * ==Why This Works==
  *
  * Both inputs (`lastFacilitators` and `lastSigners`) come from `lastOutcome`, which is consensus-agreed data that all honest nodes share.
  * This makes the computation 100% deterministic with zero dependency on local storage state.
  *
  * ==Why Not Storage-Based Lookback?==
  *
  * The previous approach read `Signed[Snapshot].proofs` from the last K snapshots in local storage. However, different nodes can have
  * different snapshot availability (due to storage eviction, async persistence timing, or different join times), causing the early/recent
  * split to produce different results on different nodes — the exact nondeterminism that causes `facilitatorsHash` forks.
  *
  * ==New Peer Onboarding==
  *
  * Peers that were NOT in `lastFacilitators` (new joiners via candidates) are never flagged as degraded — they weren't expected to sign the
  * previous snapshot. This avoids the chicken-and-egg problem where a new peer could never become eligible.
  *
  * ==Post-Rollback Behavior==
  *
  * After a network rollback where 1 node runs solo, then others join:
  *   - `lastFacilitators` = {solo} (only the solo node was a facilitator)
  *   - `lastSigners` = {solo} (the solo node signed)
  *   - `degraded` = {solo} -- {solo} = {} (empty)
  *   - New peers are NOT in lastFacilitators → not flagged
  *   - Result: all peers pass TCA, new peers can participate immediately
  *
  * ==Transient Failures==
  *
  * A peer that misses 1 round (e.g., slow network) gets excluded for 1 round but can immediately rejoin as a candidate in the next round.
  * The penalty is minor and self-correcting.
  */
trait TrailingCommonAncestorFilter[F[_]] {

  /** Compute the set of peers that should be excluded from facilitator selection.
    *
    * Excluded peers are those that were in the facilitator set for the last round but did NOT sign the finalized snapshot — i.e., they were
    * expected to participate but didn't. Peers not in `lastFacilitators` (new joiners) are never excluded.
    *
    * @param lastFacilitators
    *   the set of peers that were facilitators in the last completed round (from `lastOutcome.facilitators`)
    * @param lastSigners
    *   the set of peers that actually signed the last finalized snapshot (from `lastOutcome.finished.signedMajorityArtifact.proofs`)
    * @return
    *   Some(degradedPeers) with the set of peers to exclude, or None if inputs are insufficient
    */
  def degradedPeers(
    lastFacilitators: Set[PeerId],
    lastSigners: Set[PeerId]
  ): F[Option[Set[PeerId]]]
}

object TrailingCommonAncestorFilter {

  /** Create a TCA filter that uses only consensus-agreed data (no local storage reads).
    *
    * Compares `lastFacilitators` (who was supposed to sign) with `lastSigners` (who actually signed). The difference is the set of degraded
    * peers that should be excluded from the next round's facilitator selection.
    *
    * This approach is 100% deterministic because both inputs come from `lastOutcome`, which all honest nodes agree on.
    */
  def make[F[_]: Async]: TrailingCommonAncestorFilter[F] = new TrailingCommonAncestorFilter[F] {

    def degradedPeers(
      lastFacilitators: Set[PeerId],
      lastSigners: Set[PeerId]
    ): F[Option[Set[PeerId]]] =
      if (lastFacilitators.isEmpty || lastSigners.isEmpty)
        none[Set[PeerId]].pure[F]
      else {
        // Degraded = facilitators that were supposed to sign but didn't
        val degraded = lastFacilitators -- lastSigners
        degraded.some.pure[F]
      }
  }
}
