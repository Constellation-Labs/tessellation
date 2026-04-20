package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.ProposalQC
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
final case class VoteLock(
  highestVotedView: Option[Long],
  votedHashAtHighestView: Option[Hash],
  lockedQc: Option[ProposalQC]
) {

  def acceptVote(view: Long, proposalHash: Hash, effectiveLockedQc: Option[ProposalQC]): Either[String, VoteLock] =
    highestVotedView match {
      case Some(hv) if view < hv =>
        Left(s"lower-view vote: attempted view=$view, highestVoted=$hv")
      case Some(hv) if view == hv && votedHashAtHighestView.exists(_ != proposalHash) =>
        Left(
          s"conflicting same-view vote: view=$view already voted hash=${votedHashAtHighestView.getOrElse(Hash.empty)}, tried hash=$proposalHash"
        )
      case _ =>
        effectiveLockedQc match {
          case Some(qc) if qc.proposalHash != proposalHash =>
            Left(s"locked on QC hash=${qc.proposalHash} at view=${qc.view}, cannot vote for hash=$proposalHash")
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
