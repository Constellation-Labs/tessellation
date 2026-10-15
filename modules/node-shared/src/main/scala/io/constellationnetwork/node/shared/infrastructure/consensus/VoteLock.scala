package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.security.hash.Hash

/** Why a `CertifiedVoteLock.acceptVote` returned a Left. The `message` projection preserves the legacy structured-log string callers were
  * already emitting; `code` is a stable short label suitable for metric grouping or grep.
  */
sealed abstract class VoteRejection(val code: String) {
  def message: String
}

object VoteRejection {
  final case class LowerView(attempted: Long, highest: Long) extends VoteRejection("lower_view") {
    def message: String = s"lower-view vote: attempted view=$attempted, highestVoted=$highest"
  }
  final case class ConflictingSameView(view: Long, voted: Hash, attempted: Hash) extends VoteRejection("conflicting_same_view") {
    def message: String = s"conflicting same-view vote: view=$view already voted hash=$voted, tried hash=$attempted"
  }
  final case class LockedOnQc(lockedHash: Hash, lockedView: Long, attempted: Hash) extends VoteRejection("locked_on_qc") {
    def message: String = s"locked on QC hash=$lockedHash at view=$lockedView, cannot vote for hash=$attempted"
  }
}

/** Hash/view-agnostic vote-lock state machine.
  *
  * Callers provide the two projections that define a QC's lock identity; no serialization or hashing happens here.
  */
private[consensus] object VoteLockRules {
  final case class State[QC](
    highestVotedView: Option[Long],
    votedHashAtHighestView: Option[Hash],
    lockedQc: Option[QC]
  )

  def accept[QC](
    state: State[QC],
    view: Long,
    valueHash: Hash,
    effectiveLockedQc: Option[QC]
  )(
    qcView: QC => Long,
    qcHash: QC => Hash
  ): Either[VoteRejection, State[QC]] = {
    val strongestQc = maxByView(state.lockedQc, effectiveLockedQc)(qcView)

    state.highestVotedView match {
      case Some(highest) if view < highest =>
        Left(VoteRejection.LowerView(view, highest))
      case Some(highest) if view == highest && state.votedHashAtHighestView.exists(_ != valueHash) =>
        Left(VoteRejection.ConflictingSameView(view, state.votedHashAtHighestView.getOrElse(Hash.empty), valueHash))
      case _ =>
        strongestQc match {
          case Some(qc) if qcHash(qc) != valueHash =>
            Left(VoteRejection.LockedOnQc(qcHash(qc), qcView(qc), valueHash))
          case _ =>
            Right(State(Some(view), Some(valueHash), strongestQc))
        }
    }
  }

  def advance[QC](state: State[QC], newQc: QC)(qcView: QC => Long): State[QC] =
    state.copy(lockedQc = maxByView(state.lockedQc, Some(newQc))(qcView))

  def maxByView[QC](left: Option[QC], right: Option[QC])(qcView: QC => Long): Option[QC] =
    (left, right) match {
      case (Some(a), Some(b)) => if (qcView(a) >= qcView(b)) Some(a) else Some(b)
      case (Some(a), None)    => Some(a)
      case (None, Some(b))    => Some(b)
      case (None, None)       => None
    }
}
