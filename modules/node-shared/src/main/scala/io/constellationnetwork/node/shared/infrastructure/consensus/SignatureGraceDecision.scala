package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.concurrent.duration.FiniteDuration

/** Pure three-way signature-grace decision for snapshot finalization.
  *
  * Once a round crosses the finalization quorum it may keep collecting signatures for a bounded grace window before committing, so the
  * snapshot's proof set (which determines rewards) is not truncated to whoever happened to sign in the first 1-3ms. The window length
  * depends on which signatures are still missing:
  *
  *   1. '''Full committee signed''' (`fullCommitteeSigned`): finalize immediately -- no further signature can arrive.
  *   2. '''Core complete, committee not full''': only Tier-1 (non-quorum) signatures are outstanding, so wait the SHORT
  *      `tier1Window` to let prompt Tier-1 sigs land (reward inclusion) and then finalize. The window is measured from when Core
  *      FIRST completed, NOT from when quorum was first seen -- otherwise a round whose Core completes late (more than `tier1Window`
  *      after quorum) would skip the Tier-1 collection entirely and concentrate rewards on Core (the alpha.153 regression).
  *   3. '''Core incomplete''': a quorum-bearing Core signer is still missing, so wait the FULL `fullWindow` (measured from first quorum)
  *      for it -- this is the liveness-relevant case.
  *
  * This object is the pure decision; the caller owns the per-key [[Stamp]] state (in a `Ref`) and applies [[Eval.update]] to it. Keeping it
  * pure makes the state machine directly testable (the alpha.153 grace failure had no direct coverage).
  */
object SignatureGraceDecision {

  /** Per-round grace tracking state, carried in the caller's map keyed by round.
    *
    * @param quorumFirstSeen
    *   monotonic time at which the round first crossed the finalization quorum (the Core-incomplete window anchor).
    * @param firstCount
    *   signature count observed at `quorumFirstSeen` (diagnostic).
    * @param coreCompleteFirstSeen
    *   monotonic time at which every Core member had first signed (the Tier-1 window anchor); `None` until Core completes.
    */
  final case class Stamp(
    quorumFirstSeen: FiniteDuration,
    firstCount: Int,
    coreCompleteFirstSeen: Option[FiniteDuration]
  )

  /** How the caller should mutate its per-key stamp map for this tick. */
  sealed trait StampUpdate
  case object Leave extends StampUpdate
  case object Clear extends StampUpdate
  final case class Set(stamp: Stamp) extends StampUpdate

  /** @param update how to mutate the stamp map; @param waitMore keep waiting (do not finalize this tick); @param firstObserved this is the
    *   first tick at which quorum was seen (drives the quorum-reached metric); @param firstQuorumCount the count at first quorum;
    *   @param graceStart the window anchor used (for the grace-elapsed metric).
    */
  final case class Eval(
    update: StampUpdate,
    waitMore: Boolean,
    firstObserved: Boolean,
    firstQuorumCount: Int,
    graceStart: FiniteDuration
  )

  def evaluate(
    now: FiniteDuration,
    validCount: Int,
    canFinalize: Boolean,
    fullCommitteeSigned: Boolean,
    coreComplete: Boolean,
    existing: Option[Stamp],
    tier1Window: FiniteDuration,
    fullWindow: FiniteDuration
  ): Eval =
    if (!canFinalize)
      Eval(Leave, waitMore = false, firstObserved = false, firstQuorumCount = 0, graceStart = now)
    else if (fullCommitteeSigned)
      Eval(Clear, waitMore = false, firstObserved = true, firstQuorumCount = validCount, graceStart = now)
    else {
      val base = existing.getOrElse(Stamp(now, validCount, None))
      val firstObserved = existing.isEmpty
      val withCore =
        if (coreComplete && base.coreCompleteFirstSeen.isEmpty) base.copy(coreCompleteFirstSeen = Some(now))
        else base
      val (graceStart, window) =
        if (coreComplete) (withCore.coreCompleteFirstSeen.getOrElse(now), tier1Window)
        else (withCore.quorumFirstSeen, fullWindow)
      Eval(Set(withCore), waitMore = (now - graceStart) < window, firstObserved, withCore.firstCount, graceStart)
    }
}
