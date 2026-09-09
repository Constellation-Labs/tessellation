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
  final case class LegacyHigherViewLocked(previousView: Long, attemptedView: Long, voted: Hash, attempted: Hash)
      extends VoteRejection("legacy_higher_view_locked") {
    def message: String =
      s"legacy higher-view vote rejected: voted hash=$voted at view=$previousView, tried hash=$attempted at view=$attemptedView"
  }
  final case class LockedOnQc(lockedHash: Hash, lockedView: Long, attempted: Hash) extends VoteRejection("locked_on_qc") {
    def message: String = s"locked on QC hash=$lockedHash at view=$lockedView, cannot vote for hash=$attempted"
  }
}

/** Hash/view-agnostic vote-lock state machine.
  *
  * The legacy artifact QC and the v35 semantic-value QC deliberately remain different public types, but their safety transition is one
  * generic implementation. Callers provide the two projections that define a QC's lock identity; no serialization or hashing happens here.
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

@derive(eqv, show)
final case class VoteLock(
  highestVotedView: Option[Long],
  votedHashAtHighestView: Option[Hash],
  lockedQc: Option[ProposalQC]
) {

  def blocksLegacyViewChange: Boolean = highestVotedView.nonEmpty || lockedQc.nonEmpty

  def acceptVote(
    view: Long,
    proposalHash: Hash,
    effectiveLockedQc: Option[ProposalQC],
    mode: ViewSafetyMode
  ): Either[VoteRejection, VoteLock] =
    highestVotedView match {
      case Some(highest) if view > highest && mode == ViewSafetyMode.LegacyFreezeAfterVote =>
        Left(
          VoteRejection.LegacyHigherViewLocked(
            highest,
            view,
            votedHashAtHighestView.getOrElse(Hash.empty),
            proposalHash
          )
        )
      case _ =>
        // Once v35 is active, artifact-only QCs are compatibility data, not cross-view safety authority. Preserve lower-view and same-view
        // double-sign protection here, but authorize/reject semantic cross-view movement exclusively through CertifiedVoteLock and a
        // verified CertifiedProposalQC.
        val legacyQcAuthority = mode != ViewSafetyMode.CertifiedFullValue
        val rulesState = VoteLockRules.State(
          highestVotedView,
          votedHashAtHighestView,
          Option.when(legacyQcAuthority)(lockedQc).flatten
        )
        val rulesEffectiveQc = Option.when(legacyQcAuthority)(effectiveLockedQc).flatten
        VoteLockRules
          .accept(rulesState, view, proposalHash, rulesEffectiveQc)(
            _.view,
            _.proposalHash
          )
          .map(state =>
            VoteLock(
              state.highestVotedView,
              state.votedHashAtHighestView,
              if (legacyQcAuthority) state.lockedQc else lockedQc
            )
          )
    }

  def withAdvancedQc(newQc: ProposalQC): VoteLock = {
    val state = VoteLockRules
      .advance(VoteLockRules.State(highestVotedView, votedHashAtHighestView, lockedQc), newQc)(_.view)
    VoteLock(state.highestVotedView, state.votedHashAtHighestView, state.lockedQc)
  }
}

object VoteLock {
  val empty: VoteLock = VoteLock(None, None, None)

  def maxByView(a: Option[ProposalQC], b: Option[ProposalQC]): Option[ProposalQC] =
    VoteLockRules.maxByView(a, b)(_.view)

  def blocksLegacyViewChange(lock: Option[VoteLock], mode: ViewSafetyMode): Boolean =
    mode == ViewSafetyMode.LegacyFreezeAfterVote && lock.exists(_.blocksLegacyViewChange)
}
