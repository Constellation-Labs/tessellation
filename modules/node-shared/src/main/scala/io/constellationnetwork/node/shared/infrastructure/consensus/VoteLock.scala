package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.ProposalQC
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive

/** Layer policy for the legacy artifact-only vote wire. Global L0 uses the fail-closed bridge until v35 full-value QCs activate; Currency
  * L0 preserves its existing behavior pending its own coordinated schema rollout.
  */
sealed trait LegacyViewChangePolicy extends Product with Serializable
object LegacyViewChangePolicy {
  case object PreserveLegacy extends LegacyViewChangePolicy
  case object FreezeAfterVote extends LegacyViewChangePolicy
}

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
  final case class LegacyHigherViewLocked(previousView: Long, attemptedView: Long, voted: Hash, attempted: Hash)
      extends VoteRejection("legacy_higher_view_locked") {
    def message: String =
      s"legacy higher-view vote rejected: voted hash=$voted at view=$previousView, tried hash=$attempted at view=$attemptedView"
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

  /** The legacy vote only authenticates the artifact hash, not the complete outcome-shaping proposal envelope. Once populated, this lock
    * therefore cannot safely participate in a same-key higher view until v35 supplies a verified full-value QC.
    */
  def blocksLegacyViewChange: Boolean = highestVotedView.nonEmpty || lockedQc.nonEmpty

  def acceptVote(
    view: Long,
    proposalHash: Hash,
    effectiveLockedQc: Option[ProposalQC],
    policy: LegacyViewChangePolicy
  ): Either[VoteRejection, VoteLock] =
    highestVotedView match {
      case Some(hv) if view < hv =>
        Left(VoteRejection.LowerView(view, hv))
      case Some(hv) if view == hv && votedHashAtHighestView.exists(_ != proposalHash) =>
        Left(VoteRejection.ConflictingSameView(view, votedHashAtHighestView.getOrElse(Hash.empty), proposalHash))
      // rc.8 conservative bridge: the legacy signature covers only the artifact hash, while
      // admissions, evictions, responder evidence, rewards, and other outcome-shaping fields
      // live in the Proposal envelope. Two higher-view proposals can therefore share an
      // artifact hash and still derive different persisted outcomes. The legacy ProposalQC is
      // neither constructed nor signature-verified in production, so it cannot authorize ANY
      // higher-view re-vote after this node has signed. V35 replaces this fail-closed rule with
      // a verified QC over the complete ProposalValue.
      case Some(hv) if view > hv && policy == LegacyViewChangePolicy.FreezeAfterVote =>
        Left(VoteRejection.LegacyHigherViewLocked(hv, view, votedHashAtHighestView.getOrElse(Hash.empty), proposalHash))
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

  /** Single policy predicate shared by legacy vote emission and certified-view application. */
  def blocksLegacyViewChange(lock: Option[VoteLock], policy: LegacyViewChangePolicy): Boolean =
    policy == LegacyViewChangePolicy.FreezeAfterVote && lock.exists(_.blocksLegacyViewChange)

  def maxByView(a: Option[ProposalQC], b: Option[ProposalQC]): Option[ProposalQC] =
    (a, b) match {
      case (Some(x), Some(y)) => if (x.view >= y.view) Some(x) else Some(y)
      case (Some(x), None)    => Some(x)
      case (None, Some(y))    => Some(y)
      case (None, None)       => None
    }
}
