package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.concurrent.duration.FiniteDuration

/** Pure signature-grace decision for snapshot finalization.
  *
  * Once a round crosses the finalization quorum it may keep collecting signatures for a bounded grace window before committing, so the
  * snapshot's proof and commit-evidence sets are not truncated to whoever happened to sign in the first 1-3ms. Delegated rewards follow
  * frozen committee membership, not proofs.
  *
  *   1. '''Full committee signed''': finalize immediately -- no further signature can arrive. 2. '''Quorum but not full''': wait `window`,
  *      measured from when quorum was first seen, then finalize.
  *
  * This object is the pure decision; the caller owns the per-key [[Stamp]] state (in a `Ref`) and applies [[Eval.update]] to it.
  */
object SignatureGraceDecision {

  /** Per-round grace tracking state, carried in the caller's map keyed by round.
    *
    * @param quorumFirstSeen
    *   monotonic time at which the round first crossed the finalization quorum (the window anchor).
    * @param firstCount
    *   signature count observed at `quorumFirstSeen` (diagnostic).
    */
  final case class Stamp(quorumFirstSeen: FiniteDuration, firstCount: Int)

  /** How the caller should mutate its per-key stamp map for this tick. */
  sealed trait StampUpdate
  case object Leave extends StampUpdate
  case object Clear extends StampUpdate
  final case class Set(stamp: Stamp) extends StampUpdate

  /** @param update
    *   how to mutate the stamp map; @param waitMore keep waiting (do not finalize this tick); @param firstObserved this is the first tick
    *   at which quorum was seen (drives the quorum-reached metric); @param firstQuorumCount the count at first quorum;
    * @param graceStart
    *   the window anchor used (for the grace-elapsed metric).
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
    existing: Option[Stamp],
    window: FiniteDuration
  ): Eval =
    if (!canFinalize)
      Eval(Leave, waitMore = false, firstObserved = false, firstQuorumCount = 0, graceStart = now)
    else if (fullCommitteeSigned)
      Eval(Clear, waitMore = false, firstObserved = true, firstQuorumCount = validCount, graceStart = now)
    else {
      val stamp = existing.getOrElse(Stamp(now, validCount))
      Eval(Set(stamp), waitMore = (now - stamp.quorumFirstSeen) < window, existing.isEmpty, stamp.firstCount, stamp.quorumFirstSeen)
    }
}
