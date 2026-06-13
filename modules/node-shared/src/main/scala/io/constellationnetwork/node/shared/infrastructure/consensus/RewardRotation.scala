package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId

/** Bounded one-slot Tier-1 reward rotation.
  *
  * ==Why this exists==
  *
  * Snapshot rewards go to the snapshot signers (`Rewards.distribute` splits the pool evenly across `lastArtifact.proofs.map(_.id)`). The
  * active signing set is sticky: `ActiveFacilitatorAdmission.recentSignerRank` orders by `-recentSignerCount`, so the peers that already
  * sign most often keep their seats and reward share concentrates on a stale group while healthy peers that are merely waiting their turn
  * never sign. This object closes that gap by deterministically rotating exactly ONE Tier-1 (non-quorum-bearing) signing slot per epoch,
  * cycling every demonstrated-live peer through signing duty over time.
  *
  * ==Scope: Tier 1 only, never Core==
  *
  * Core is the LIVENESS quorum denominator. Rotating a Core seat would change the quorum membership and risk wedging consensus, so Core is
  * NEVER touched here -- only a single Tier-1 seat is swapped. Tier-1 peers sign and earn but do not gate the cert quorum, so swapping one is
  * reward-fair without any liveness cost (`CommitteeBuilder` scaladoc, "Reward and signer pool"). The churn is bounded to one slot per epoch
  * boundary so the committee stays stable.
  *
  * ==Determinism contract (read before changing any input)==
  *
  * This rotation feeds the committee -> `roundStartFacilitators` -> `facilitatorsHash`, so a divergent swap on any node forks the cluster
  * (the alpha.92/129/147 class). Every input is therefore either chain-derived or a deterministic function of consensus-agreed state, and
  * every ordering ends in a `PeerId` tiebreak:
  *
  *   - `key` is the consensus-agreed round ordinal; the epoch-boundary test `key.value % epochRounds == 0` is a pure integer comparison.
  *   - `idle` / `tenure` are `ControllerEvidenceDerivation.idleWindows` / `tenureWindows` over the SIGNED evidence window -- entry counts,
  *     never wall-clock.
  *   - `eligibleWaiting` is `candidates intersect recentParticipants minus core minus tier1`, all consensus-agreed sets.
  *   - `lotteryHash` is reused verbatim from `FacilitatorSelector.rendezvousScore` (the existing rendezvous-hashing tiebreak), so the lottery
  *     is the same SHA-256 mixing every node already computes for facilitator selection -- no new hashing scheme.
  *
  * There is no randomness, no node-local readiness observation, and no mutable state. Two honest nodes holding the same `key`, evidence
  * window, and candidate set compute the identical `(rotateOut, rotateIn)` (or identical `None`).
  *
  * ==Inert by default==
  *
  * `epochRounds <= 0` always returns `None`, so with the feature disabled (the default for every environment) `CommitteeBuilder.build` is
  * byte-identical to its pre-rotation behavior.
  */
object RewardRotation {

  /** Pick the single Tier-1 slot to rotate this round, or `None` when rotation does not apply.
    *
    * Returns `Some((rotateOut, rotateIn))` only when ALL of:
    *   - `epochRounds > 0` AND `key` is an epoch boundary (`key.value % epochRounds == 0`),
    *   - `eligibleWaiting` is nonempty (there is a demonstrated-live peer outside core and tier1 waiting for a turn), AND
    *   - `tier1` is nonempty (there is a Tier-1 seat to give up).
    *
    * When it fires:
    *   - `rotateIn` = the `eligibleWaiting` peer with the largest `idle` count (longest-overdue), `lotteryHash(peer, key)` descending as the
    *     fair tiebreak among equally-idle peers, then `PeerId` lex as the final deterministic tiebreak.
    *   - `rotateOut` = the `tier1` peer with the largest `tenure` count (longest-serving), `PeerId` lex tiebreak.
    *
    * Pure, deterministic, total.
    *
    * @param key
    *   the consensus-agreed round ordinal (the next round's key). The epoch boundary is derived from `key.value`.
    * @param core
    *   the Core committee for the round. Present only so the caller's `eligibleWaiting` precondition is self-documenting; this function never
    *   reads or alters it (Core is never rotated).
    * @param tier1
    *   the current Tier-1 set. The rotated-out peer is drawn from here.
    * @param eligibleWaiting
    *   demonstrated-live candidates NOT in core and NOT in tier1 -- the pool the rotated-in peer is drawn from. The caller computes this as
    *   `candidates intersect ControllerEvidenceDerivation.recentParticipants minus core minus tier1`.
    * @param idle
    *   `ControllerEvidenceDerivation.idleWindows(evidence, _)` -- trailing entries since the peer last signed (overdue measure).
    * @param tenure
    *   `ControllerEvidenceDerivation.tenureWindows(evidence, _)` -- consecutive trailing entries the peer has signed (serving measure).
    * @param epochRounds
    *   epoch length in ordinals. `<= 0` disables rotation.
    * @param lotteryHash
    *   `(peer, key) => BigInt` deterministic rendezvous score, reused from `FacilitatorSelector.rendezvousScore`. Used only as the
    *   equally-idle tiebreak so the lottery spreads turns fairly when several peers are equally overdue.
    */
  def rotateOneTier1(
    key: SnapshotOrdinal,
    core: Set[PeerId],
    tier1: List[PeerId],
    eligibleWaiting: List[PeerId],
    idle: PeerId => Int,
    tenure: PeerId => Int,
    epochRounds: Int,
    lotteryHash: (PeerId, SnapshotOrdinal) => BigInt
  ): Option[(PeerId, PeerId)] = {
    val _ = core
    val isEpochBoundary = epochRounds > 0 && key.value.value % epochRounds.toLong == 0L

    if (!isEpochBoundary || eligibleWaiting.isEmpty || tier1.isEmpty) None
    else {
      val rotateIn = eligibleWaiting.minBy(pid => (-idle(pid), -lotteryHash(pid, key), pid.value.value))
      val rotateOut = tier1.minBy(pid => (-tenure(pid), pid.value.value))

      Some((rotateOut, rotateIn))
    }
  }
}
