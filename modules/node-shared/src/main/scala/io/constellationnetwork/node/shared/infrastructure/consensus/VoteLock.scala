package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.ProposalQC
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive

/** Why a `VoteLock.acceptVote` (or `ConsensusStorage.tryLockVote`) returned a Left. The `message` projection preserves the legacy
  * structured-log string callers were already emitting; `code` is a stable short label suitable for metric grouping or grep.
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

@derive(eqv, show)
final case class VoteLock(
  highestVotedView: Option[Long],
  votedHashAtHighestView: Option[Hash],
  lockedQc: Option[ProposalQC]
) {

  def acceptVote(view: Long, proposalHash: Hash, effectiveLockedQc: Option[ProposalQC]): Either[VoteRejection, VoteLock] =
    highestVotedView match {
      case Some(hv) if view < hv =>
        Left(VoteRejection.LowerView(view, hv))
      case Some(hv) if view == hv && votedHashAtHighestView.exists(_ != proposalHash) =>
        Left(VoteRejection.ConflictingSameView(view, votedHashAtHighestView.getOrElse(Hash.empty), proposalHash))
      case _ =>
        effectiveLockedQc match {
          case Some(qc) if qc.proposalHash != proposalHash =>
            Left(VoteRejection.LockedOnQc(qc.proposalHash, qc.view, proposalHash))
          case _ =>
            Right(
              VoteLock(
                highestVotedView = Some(view),
                votedHashAtHighestView = Some(proposalHash),
                lockedQc = effectiveLockedQc.orElse(lockedQc)
              )
            )
        }
    }

  def withAdvancedQc(newQc: ProposalQC): VoteLock =
    lockedQc match {
      case Some(current) if current.view >= newQc.view => this
      case _                                           => copy(lockedQc = Some(newQc))
    }
}

object VoteLock {
  val empty: VoteLock = VoteLock(None, None, None)

  def maxByView(a: Option[ProposalQC], b: Option[ProposalQC]): Option[ProposalQC] =
    (a, b) match {
      case (Some(x), Some(y)) => if (x.view >= y.view) Some(x) else Some(y)
      case (Some(x), None)    => Some(x)
      case (None, Some(y))    => Some(y)
      case (None, None)       => None
    }
}
