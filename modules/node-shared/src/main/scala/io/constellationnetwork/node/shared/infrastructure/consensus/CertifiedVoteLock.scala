package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus.CertifiedProposalQC
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** Local v35 vote lock over the complete certified ProposalValue hash.
  *
  * This deliberately does not reuse the legacy artifact-only VoteLock. The two hashes have different meanings and mixing them would make it
  * possible to treat an artifact QC as certification of the outcome envelope again.
  */
@derive(eqv, show, encoder, decoder)
final case class CertifiedVoteLock(
  highestVotedView: Option[Long],
  votedValueHashAtHighestView: Option[Hash],
  lockedQc: Option[CertifiedProposalQC]
) {

  def acceptVote(
    view: Long,
    valueHash: Hash,
    effectiveLockedQc: Option[CertifiedProposalQC]
  ): Either[VoteRejection, CertifiedVoteLock] =
    VoteLockRules
      .accept(
        VoteLockRules.State(highestVotedView, votedValueHashAtHighestView, lockedQc),
        view,
        valueHash,
        effectiveLockedQc
      )(
        _.value.committedView,
        _.valueHash
      )
      .map(state => CertifiedVoteLock(state.highestVotedView, state.votedHashAtHighestView, state.lockedQc))

  /** Records a verified QC for the value being prepared. Adopting a QC signs no OutcomeVote, so a same-view conflict with this node's own
    * prepare vote does not block it: two QCs for different values at one view would need a Core peer to vote twice in that view. Vote
    * history is left untouched, so LowerView is still refused (an older QC must not authorize a commit after a higher-view prepare), and a
    * stronger or equal-view locked QC for another value still wins.
    */
  def acceptVerifiedQc(qc: CertifiedProposalQC): Either[VoteRejection, CertifiedVoteLock] =
    acceptVote(qc.value.committedView, qc.valueHash, qc.some).leftFlatMap {
      case _: VoteRejection.ConflictingSameView =>
        CertifiedVoteLock.maxByView(lockedQc, qc.some) match {
          case Some(strongest) if strongest.valueHash =!= qc.valueHash =>
            VoteRejection.LockedOnQc(strongest.valueHash, strongest.value.committedView, qc.valueHash).asLeft[CertifiedVoteLock]
          case strongest => copy(lockedQc = strongest).asRight[VoteRejection]
        }
      case other => other.asLeft[CertifiedVoteLock]
    }

  def withAdvancedQc(newQc: CertifiedProposalQC): CertifiedVoteLock = {
    val state = VoteLockRules
      .advance(VoteLockRules.State(highestVotedView, votedValueHashAtHighestView, lockedQc), newQc)(_.value.committedView)
    CertifiedVoteLock(state.highestVotedView, state.votedHashAtHighestView, state.lockedQc)
  }
}

object CertifiedVoteLock {
  val empty: CertifiedVoteLock = CertifiedVoteLock(None, None, None)

  def maxByView(
    left: Option[CertifiedProposalQC],
    right: Option[CertifiedProposalQC]
  ): Option[CertifiedProposalQC] =
    VoteLockRules.maxByView(left, right)(_.value.committedView)
}
