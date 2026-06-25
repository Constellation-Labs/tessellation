package io.constellationnetwork.node.shared.infrastructure.consensus

/** Canonical, side-effect-free peer participation-quality predicates, shared by the consensus admission path (`ActiveFacilitatorAdmission`)
  * and operator-facing views (dag-l0 `HttpApi` committee view) so the two cannot drift apart (see `feedback_share_logic_no_drift`:
  * consensus-adjacent logic must not be reimplemented).
  *
  * All comparisons use EXACT integer (cross-multiplied) math -- never `Double` division -- so the classification is byte-deterministic
  * across JVM / JIT / architecture wherever it feeds a consensus decision. The committee view previously reimplemented the same predicate
  * in `Double`, which both risked LSB divergence and silently drifted from this logic when the admission filter changed.
  */
object PeerQualityClassifier {

  /** Fixed-point scale for the participation ratio (`completed / participated`). */
  val ParticipationRatioScale: Long = 1000000L

  def minParticipationRatioScaled(minParticipationRatio: Double): Long =
    math.round(minParticipationRatio * ParticipationRatioScale)

  def participationRatioScaled(completed: Int, participated: Int): Long =
    if (participated <= 0) 0L else completed.toLong * ParticipationRatioScale / participated.toLong

  /** True when `completed / participated >= minParticipationRatio`, as a cross-multiplied integer comparison (no division, no `Double`).
    * `minParticipationRatioScaled` is `minParticipationRatioScaled(ratio)`.
    */
  def meetsParticipationRatio(completed: Int, participated: Int, minParticipationRatioScaled: Long): Boolean =
    participated > 0 && completed.toLong * ParticipationRatioScale >= minParticipationRatioScaled * participated.toLong

  /** A peer is "chronic" once it has at least `minObservations` participation observations AND its participation ratio is below
    * `minParticipationRatio`. This is exactly the consensus admission chronic filter; callers supply their own observation floor (the
    * consensus path and the operator view use different display floors by design, but share this predicate so the ratio computation cannot
    * diverge).
    */
  def isChronic(completed: Int, participated: Int, minObservations: Int, minParticipationRatio: Double): Boolean =
    participated >= minObservations &&
      !meetsParticipationRatio(completed, participated, minParticipationRatioScaled(minParticipationRatio))
}
